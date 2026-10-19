import { render, screen } from "@testing-library/react";
import LogbookPage from "@/app/logbuch/page";
import vectors from "@/generated/export-vectors.json";
import {
  AIRPORT_COORDINATES,
  buildIcs,
  foldLine,
  itineraryCalendar,
  logbookStats,
  logbookToCsv,
  mergeLogbook,
  parseCsv,
  parseLogbookCsv,
  type CalendarFlight,
  type Coordinates,
} from "@/lib/exports";
import type { LogbookEntry } from "@/lib/settings";
import type { SearchResponse } from "@/lib/types";
import fixture from "./fixtures/search-response.json";

type VectorEntry = (typeof vectors.logbook.entries)[number];
const toEntry = (e: VectorEntry): LogbookEntry => ({
  id: e.id,
  date: e.date,
  status: e.status as LogbookEntry["status"],
  airline: e.airline,
  flightNumber: e.flight_number,
  origin: e.origin,
  destination: e.destination,
  aircraftTypeId: e.aircraft_type_id,
  notes: e.notes,
});
const entries = vectors.logbook.entries.map(toEntry);
const known = () => true;

describe("Logbuch-Statistik (gemeinsame Testvektoren)", () => {
  it("entspricht der Referenzimplementierung", () => {
    const s = logbookStats(entries, vectors.logbook.wishlist, vectors.logbook.airports as unknown as Coordinates);
    const e = vectors.logbook.expected_stats;
    expect(s).toEqual({
      flights: e.flights,
      planned: e.planned,
      types: e.types,
      distanceKm: e.distance_km,
      unknownDistance: e.unknown_distance,
      longest: e.longest,
      topTypes: e.top_types,
      wishlistFlown: e.wishlist_flown,
      wishlistTotal: e.wishlist_total,
    });
  });

  it("leeres Logbuch", () => {
    expect(logbookStats([], [])).toMatchObject({ flights: 0, distanceKm: 0, longest: null, topTypes: [] });
  });

  it("kennt die Flughafenkoordinaten aus dem Backend-Datensatz", () => {
    expect(AIRPORT_COORDINATES.ZRH).toEqual([47.46, 8.55]);
  });
});

describe("Logbuch-CSV", () => {
  it("Export entspricht der Referenz (RFC 4180, CRLF)", () => {
    expect(logbookToCsv(entries)).toBe(vectors.logbook.expected_csv);
  });

  it("Rundreise Export → Import erhält alle Felder", () => {
    const back = parseLogbookCsv(logbookToCsv(entries), known);
    expect(back.errors).toEqual([]);
    expect(back.entries.map(({ id: _id, ...rest }) => rest)).toEqual(entries.map(({ id: _id, ...rest }) => rest));
  });

  it("Parser: Anführungszeichen, Kommas, Zeilenumbrüche, BOM, LF", () => {
    expect(parseCsv('﻿a,b\n"x, ""y""","z\nw"\n')).toEqual([["a", "b"], ['x, "y"', "z\nw"]]);
  });

  it("meldet fehlerhafte Zeilen und importiert den Rest", () => {
    const csv = [
      "date,status,airline,flight_number,origin,destination,aircraft_type_id,notes",
      "2026-01-01,flown,LX,38,ZRH,SFO,A380,",
      "01.01.2026,flown,LX,38,ZRH,SFO,A380,",
      "2026-01-02,maybe,LX,38,ZRH,SFO,A380,",
      "2026-01-03,flown,LX,38,ZURICH,SFO,A380,",
      "2026-01-04,flown,LX,38,ZRH,SFO,XYZ,",
    ].join("\n");
    const r = parseLogbookCsv(csv, (id) => id === "A380");
    expect(r.entries).toHaveLength(1);
    expect(r.errors).toHaveLength(4);
    expect(r.errors[0]).toContain("Zeile 3");
  });

  it("verlangt die Kopfzeile", () => {
    expect(parseLogbookCsv("a,b\n1,2", known).errors[0]).toContain("Kopfzeile");
  });

  it("importiert identische Einträge nicht doppelt", () => {
    const { merged, added } = mergeLogbook(entries, entries.map((e) => ({ ...e, id: `${e.id}-neu` })));
    expect(added).toBe(0);
    expect(merged).toHaveLength(entries.length);
  });
});

describe("Kalender-Export (.ics)", () => {
  const flights: CalendarFlight[] = vectors.ics.flights.map((f) => ({
    uid: f.uid,
    carrier: f.carrier,
    flightNumber: f.flight_number,
    origin: f.origin,
    destination: f.destination,
    departureUtc: f.departure_utc,
    arrivalUtc: f.arrival_utc,
    departureLocal: f.departure_local,
    arrivalLocal: f.arrival_local,
    aircraft: f.aircraft,
    aircraftStatus: f.aircraft_status,
  }));

  it("entspricht byte-genau der Referenz", () => {
    expect(buildIcs(vectors.ics.calendar_name, flights, new Date(vectors.ics.now))).toBe(vectors.ics.expected);
  });

  it("faltet nach höchstens 75 Oktetten, ohne Zeichen zu zerteilen", () => {
    const lines = foldLine(`SUMMARY:${"✈ä".repeat(40)}`);
    const enc = new TextEncoder();
    expect(lines.length).toBeGreaterThan(1);
    lines.forEach((l) => expect(enc.encode(l).length).toBeLessThanOrEqual(75));
    expect(lines.map((l, i) => (i ? l.slice(1) : l)).join("")).toBe(`SUMMARY:${"✈ä".repeat(40)}`);
  });

  it("baut aus einer Suchergebnis-Reise je Segment einen Termin", () => {
    const it0 = (fixture as unknown as SearchResponse).results[0].itinerary;
    const cal = itineraryCalendar(it0);
    expect(cal.flights).toHaveLength(it0.legs.reduce((n, l) => n + l.segments.length, 0));
    expect(cal.name).toContain("nicht gebucht");
    const ics = buildIcs(cal.name, cal.flights, new Date("2026-10-19T00:00:00Z"));
    expect(ics.match(/BEGIN:VEVENT/g)).toHaveLength(cal.flights.length);
  });
});

describe("Logbuch-Seite", () => {
  beforeEach(() => window.localStorage.clear());

  it("zeigt Statistik und Export für gespeicherte Einträge", async () => {
    window.localStorage.setItem("ato.logbook.v1", JSON.stringify(entries));
    render(<LogbookPage />);
    expect(await screen.findByRole("heading", { name: "Statistik" })).toBeInTheDocument();
    expect(await screen.findByText(/Längster Flug: ZRH → SFO/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "CSV exportieren" })).toBeEnabled();
    expect(screen.getByText(/ohne bekannte Strecke/)).toBeInTheDocument();
  });

  it("deaktiviert den Export ohne Einträge", async () => {
    render(<LogbookPage />);
    expect(await screen.findByRole("button", { name: "CSV exportieren" })).toBeDisabled();
  });
});
