import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AircraftHistoryPanel } from "@/components/AircraftHistoryPanel";
import { PriceCalendar } from "@/components/PriceCalendar";
import { ProviderBanner } from "@/components/ProviderBanner";
import { ApiClient } from "@/lib/api";
import { DEFAULT_FILTER, filterAndSort } from "@/lib/results";
import type { CalendarCell, SearchResponse } from "@/lib/types";
import fixture from "./fixtures/search-response.json";

const response = fixture as unknown as SearchResponse;

const cells: CalendarCell[] = [
  { departure_date: "2026-12-18", nights: 14, min_price: 900, currency: "EUR", offers: 3, itinerary_id: "a" },
  { departure_date: "2026-12-19", nights: 14, min_price: 700, currency: "EUR", offers: 1, itinerary_id: "b" },
  { departure_date: "2026-12-19", nights: 10, min_price: 1100, currency: "EUR", offers: 2, itinerary_id: "c" },
];

describe("Preiskalender", () => {
  it("zeigt günstigste Kombination und filtert per Klick", async () => {
    const onSelect = vi.fn();
    render(<PriceCalendar cells={cells} selected={{ date: null, nights: null }} onSelect={onSelect} />);
    expect(screen.getByText(/19\.12\., 14 Nächte/)).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /Abflug/ })).toHaveLength(3);
    expect(screen.getByLabelText("keine Angebote")).toBeInTheDocument(); // 18.12. mit 10 Nächten
    await userEvent.click(screen.getByRole("button", { name: /Abflug 19\.12\., 14 Nächte/ }));
    expect(onSelect).toHaveBeenCalledWith("2026-12-19", 14);
  });

  it("rendert nichts ohne Daten", () => {
    const { container } = render(<PriceCalendar cells={[]} selected={{ date: null, nights: null }} onSelect={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("Datums- und Nächtefilter wirken auf die Ergebnisliste", () => {
    const first = response.results[0].itinerary;
    const filtered = filterAndSort(response.results, { ...DEFAULT_FILTER, departureDate: first.outbound_date, nights: first.nights }, "score");
    expect(filtered.length).toBeGreaterThan(0);
    expect(filtered.every((r) => r.itinerary.outbound_date === first.outbound_date && r.itinerary.nights === first.nights)).toBe(true);
  });

  it("Fixture des Backends enthält den Kalender", () => {
    expect(response.calendar?.length).toBeGreaterThan(0);
  });
});

describe("Typverlauf", () => {
  it("lädt beobachtete Flugzeugtypen auf Knopfdruck", async () => {
    const fetcher = vi.fn(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            status: "ok", flight: "LX 38", callsign: "SWR38", origin: "ZRH", days_checked: 5, checked_at: "2026-10-18T10:00:00Z",
            source: "OpenSky", message: null,
            observations: [{ date: "2026-10-17", callsign: "SWR38", icao24: "4b1801", registration: "HB-JMA", type_code: "A343", aircraft_type_id: "A343", aircraft_name: "Airbus A340-300" }],
            distribution: [{ aircraft_type_id: "A343", name: "Airbus A340-300", count: 4, share: 0.8 }, { aircraft_type_id: "A333", name: "Airbus A330-300", count: 1, share: 0.2 }],
          }),
          { status: 200 },
        ),
      ),
    );
    const api = new ApiClient("https://api.example.org", fetcher as unknown as typeof fetch);
    render(<AircraftHistoryPanel api={api} carrier="LX" flightNumber="38" origin="ZRH" wishlist={["A343"]} />);
    await userEvent.click(screen.getByRole("button", { name: /Typverlauf LX 38 prüfen/ }));
    expect(await screen.findByText(/Airbus A340-300 · 4× \(80 %\)/)).toHaveClass("wish");
    expect(screen.getByText(/keine Garantie/)).toBeInTheDocument();
    expect(String((fetcher.mock.calls[0] as unknown[])[0])).toContain("/api/v1/aircraft-history?carrier=LX&flight_number=38&origin=ZRH&days=5");
  });

  it("zeigt Hinweis, wenn keine Beobachtung vorliegt", async () => {
    const fetcher = () =>
      Promise.resolve(new Response(JSON.stringify({ status: "no_observations", flight: "LX 38", origin: "ZRH", days_checked: 5, checked_at: "x", source: "OpenSky", observations: [], distribution: [], message: "Keine Beobachtung von SWR38." }), { status: 200 }));
    render(<AircraftHistoryPanel api={new ApiClient("https://x.org", fetcher as unknown as typeof fetch)} carrier="LX" flightNumber="38" origin="ZRH" wishlist={[]} />);
    await userEvent.click(screen.getByRole("button"));
    expect(await screen.findByText("Keine Beobachtung von SWR38.")).toBeInTheDocument();
  });
});

describe("Geschätzte Preise", () => {
  it("werden als kostenlose Schätzung gekennzeichnet", () => {
    render(<ProviderBanner provider={{ name: "travelpayouts", configured: true, mode: "estimated", message: "Richtwerte" }} backendError={null} />);
    expect(screen.getByRole("status")).toHaveTextContent("Kostenlose Preisdaten (geschätzt)");
  });
});
