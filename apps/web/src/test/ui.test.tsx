import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AboutPage from "@/app/about/page";
import { AppShell } from "@/components/AppShell";
import { ProviderBanner } from "@/components/ProviderBanner";
import { ResultCard } from "@/components/ResultCard";
import { SearchForm, defaultFormValues } from "@/components/SearchForm";
import type { SearchResponse } from "@/lib/types";
import fixture from "./fixtures/search-response.json";

vi.mock("next/navigation", () => ({ usePathname: () => "/about/" }));

const response = fixture as unknown as SearchResponse;
const REPO = "Atomkarnickel/aviation-trip-optimizer";

describe("Navigation", () => {
  it("bietet Seitenleiste und mobile Bottom-Navigation mit aktiver Seite", () => {
    render(<AppShell><p>Inhalt</p></AppShell>);
    const navs = screen.getAllByRole("navigation");
    expect(navs).toHaveLength(2);
    for (const nav of navs) {
      expect(within(nav).getAllByRole("link").filter((l) => l.getAttribute("aria-current") === "page").map((l) => l.textContent)).toEqual(["About"]);
      for (const label of ["Entdecken", "Favoriten", "Logbuch", "Profil", "About"]) {
        expect(within(nav).getByRole("link", { name: new RegExp(label) })).toBeInTheDocument();
      }
    }
    expect(screen.getByText("Zum Inhalt springen")).toHaveAttribute("href", "#inhalt");
  });
});

describe("Suchformular", () => {
  it("zeigt verständliche, zugängliche Fehlermeldungen und sendet nicht", async () => {
    const onSubmit = vi.fn();
    render(<SearchForm initial={{ ...defaultFormValues([], []), origins: "" }} busy={false} onSubmit={onSubmit} />);
    await userEvent.click(screen.getByRole("button", { name: "Reisen suchen" }));
    expect(screen.getByLabelText("Abflughäfen")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("alert")).toHaveTextContent("Mindestens einen Abflughafen");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("sendet gültige Werte inkl. Wunschflugzeug", async () => {
    const onSubmit = vi.fn();
    render(<SearchForm initial={defaultFormValues(["ZRH"], [])} busy={false} onSubmit={onSubmit} />);
    await userEvent.click(screen.getByRole("button", { name: /A380-800/ }));
    await userEvent.click(screen.getByRole("button", { name: "Reisen suchen" }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit.mock.calls[0][0].desiredAircraft).toContain("A380");
  });

  it("ist während der Suche gesperrt (kein Doppelklick)", () => {
    render(<SearchForm initial={defaultFormValues(["ZRH"], [])} busy onSubmit={vi.fn()} />);
    expect(screen.getByRole("button", { name: /Suche läuft/ })).toBeDisabled();
  });
});

describe("Ergebnisdarstellung", () => {
  it("zeigt Preis, Flugzeugtypen, Demo-Kennzeichnung und keinen Buchungslink für Demo", () => {
    const item = response.results.find((r) => r.wishlist_hits.length > 0)!;
    render(
      <ResultCard item={{ ...item, rank: 1 }} favorite={false} onToggleFavorite={vi.fn()} compareSelected={false} onToggleCompare={vi.fn()} compareDisabled={false} />,
    );
    const card = screen.getByRole("article");
    expect(within(card).getByText("DEMO")).toBeInTheDocument();
    expect(within(card).getByText(/★ Airbus A380-800/)).toBeInTheDocument();
    expect(within(card).getByText(/Demo – nicht buchbar/)).toBeInTheDocument();
    expect(within(card).queryByRole("link")).toBeNull();
    expect(within(card).getAllByRole("meter")).toHaveLength(7);
    expect(within(card).getAllByText(/Flugzeugtypen sind geplant, nicht garantiert/).length).toBeGreaterThan(0);
  });

  it("zeigt Airline-Link nur als Website, nicht als Deep-Link", () => {
    const base = response.results[0];
    const item = {
      ...base,
      itinerary: {
        ...base.itinerary,
        is_demo: false,
        price_source: "live_offer" as const,
        booking_options: [{ kind: "airline_website", label: "SWISS – Website", url: "https://www.swiss.com", is_deep_link: false }],
      },
    };
    render(<ResultCard item={item} favorite onToggleFavorite={vi.fn()} compareSelected={false} onToggleCompare={vi.fn()} compareDisabled={false} />);
    const link = screen.getByRole("link", { name: /Zur Airline-Website/ });
    expect(link).toHaveAttribute("href", "https://www.swiss.com");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
    expect(screen.getByRole("button", { name: /Gespeichert/ })).toHaveAttribute("aria-pressed", "true");
  });
});

describe("Provider-Status", () => {
  it("täuscht ohne Provider keine Live-Suche vor", () => {
    render(<ProviderBanner provider={{ name: "none", configured: false, mode: "none", message: "Kein Provider." }} backendError={null} />);
    expect(screen.getByRole("status")).toHaveTextContent("Keine Live-Flugdaten konfiguriert");
  });
  it("meldet fehlendes Backend", () => {
    render(<ProviderBanner provider={null} backendError="Backend nicht erreichbar." />);
    expect(screen.getByRole("status")).toHaveTextContent("Backend nicht verbunden");
  });
});

describe("About", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("zeigt Web-Version und neueste APK getrennt mit dynamischem Download-Link", async () => {
    const body = [
      {
        tag_name: "v1.4.0",
        draft: false,
        prerelease: false,
        published_at: "2026-10-10T08:00:00Z",
        html_url: `https://github.com/${REPO}/releases/tag/v1.4.0`,
        body: "- Vergleichsmodus",
        assets: [
          {
            name: "aviation-optimizer-1.4.0.apk",
            size: 9000000,
            browser_download_url: `https://github.com/${REPO}/releases/download/v1.4.0/aviation-optimizer-1.4.0.apk`,
          },
        ],
      },
    ];
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }))));
    render(<AboutPage />);
    expect(screen.getByText(/^1\.0\.0 \(Build/)).toBeInTheDocument();
    const link = await screen.findByRole("link", { name: /Neueste APK herunterladen \(v1\.4\.0\)/ });
    expect(link).toHaveAttribute("href", `https://github.com/${REPO}/releases/download/v1.4.0/aviation-optimizer-1.4.0.apk`);
    expect(screen.getByText("1.4.0")).toBeInTheDocument();
    expect(screen.getByText(/nur für Android-Geräte/)).toBeInTheDocument();
    expect(screen.getByText(/Unbekannte Apps installieren/)).toBeInTheDocument();
  });

  it("bleibt bei API-Ausfall nutzbar und kennzeichnet den Status", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new TypeError("offline"))));
    render(<AboutPage />);
    await waitFor(() => expect(screen.getByText("Versionsprüfung nicht möglich")).toBeInTheDocument());
    expect(screen.queryByRole("link", { name: /Neueste APK/ })).toBeNull();
    expect(screen.getByText(/^1\.0\.0 \(Build/)).toBeInTheDocument();
  });
});
