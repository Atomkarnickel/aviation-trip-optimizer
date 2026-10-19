// Logbuch-Statistik, Logbuch-CSV und Kalender-Export (.ics).
// Referenz: scripts/gen_export_vectors.py – Web und Android werden gegen shared/test-vectors/exports.json getestet.
import airportData from "@/generated/airports.json";
import { aircraftName } from "@/lib/aircraft";
import type { LogbookEntry } from "@/lib/settings";
import type { ScoredItinerary } from "@/lib/types";

export type Coordinates = Record<string, [number, number]>;

export const AIRPORT_COORDINATES: Coordinates = Object.fromEntries(
  (airportData.airports as { iata: string; lat: number; lon: number }[]).map((a) => [a.iata, [a.lat, a.lon]]),
);

export function distanceKm(a: [number, number], b: [number, number]): number {
  const rad = (d: number) => (d * Math.PI) / 180;
  const [lat1, lon1, lat2, lon2] = [rad(a[0]), rad(a[1]), rad(b[0]), rad(b[1])];
  const h = Math.sin((lat2 - lat1) / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin((lon2 - lon1) / 2) ** 2;
  return 2 * 6371.0 * Math.asin(Math.sqrt(h));
}

// Python round(): kaufmännisch auf gerade Zahl bei exakt .5 – bei Kilometersummen praktisch irrelevant,
// aber für identische Ergebnisse auf allen Plattformen nachgebildet.
function roundHalfEven(x: number): number {
  const r = Math.round(x);
  return Math.abs(x % 1) === 0.5 && r % 2 !== 0 ? r - 1 : r;
}

export interface LogbookStats {
  flights: number;
  planned: number;
  types: number;
  distanceKm: number;
  unknownDistance: number;
  longest: { id: string; km: number } | null;
  topTypes: { id: string; count: number }[];
  wishlistFlown: string[];
  wishlistTotal: number;
}

export function logbookStats(entries: LogbookEntry[], wishlist: string[], airports: Coordinates = AIRPORT_COORDINATES): LogbookStats {
  const flown = entries.filter((e) => e.status === "flown");
  let total = 0;
  let unknown = 0;
  let longest: { id: string; km: number } | null = null;
  const counts = new Map<string, number>();
  for (const e of flown) {
    counts.set(e.aircraftTypeId, (counts.get(e.aircraftTypeId) ?? 0) + 1);
    const a = airports[e.origin];
    const b = airports[e.destination];
    if (!a || !b) {
      unknown += 1;
      continue;
    }
    const d = distanceKm(a, b);
    total += d;
    if (!longest || d > longest.km) longest = { id: e.id, km: d };
  }
  const topTypes = [...counts.entries()]
    .sort((x, y) => y[1] - x[1] || (x[0] < y[0] ? -1 : x[0] > y[0] ? 1 : 0))
    .slice(0, 3)
    .map(([id, count]) => ({ id, count }));
  return {
    flights: flown.length,
    planned: entries.length - flown.length,
    types: counts.size,
    distanceKm: roundHalfEven(total),
    unknownDistance: unknown,
    longest: longest ? { id: longest.id, km: roundHalfEven(longest.km) } : null,
    topTypes,
    wishlistFlown: wishlist.filter((w) => counts.has(w)).sort(),
    wishlistTotal: wishlist.length,
  };
}

// ---------- CSV ----------

const CSV_COLUMNS = ["date", "status", "airline", "flight_number", "origin", "destination", "aircraft_type_id", "notes"] as const;

function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

function entryValues(e: LogbookEntry): string[] {
  return [e.date, e.status, e.airline, e.flightNumber, e.origin, e.destination, e.aircraftTypeId, e.notes];
}

export function logbookToCsv(entries: LogbookEntry[]): string {
  const lines = [CSV_COLUMNS.join(","), ...entries.map((e) => entryValues(e).map(csvField).join(","))];
  return lines.join("\r\n") + "\r\n";
}

/** RFC-4180-Parser (Anführungszeichen, eingebettete Kommas und Zeilenumbrüche, CRLF oder LF). */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  const src = text.replace(/^\uFEFF/, "");
  for (let i = 0; i < src.length; i++) {
    const c = src[i];
    if (quoted) {
      if (c === '"') {
        if (src[i + 1] === '"') {
          field += '"';
          i++;
        } else quoted = false;
      } else field += c === "\r" && src[i + 1] === "\n" ? "" : c;
    } else if (c === '"' && field === "") quoted = true;
    else if (c === ",") {
      row.push(field);
      field = "";
    } else if (c === "\n" || c === "\r") {
      if (c === "\r" && src[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else field += c;
  }
  if (field !== "" || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows.filter((r) => !(r.length === 1 && r[0] === ""));
}

export interface CsvImport {
  entries: LogbookEntry[];
  errors: string[];
}

export function parseLogbookCsv(text: string, knownAircraft: (id: string) => boolean): CsvImport {
  const rows = parseCsv(text);
  if (rows.length === 0) return { entries: [], errors: ["Datei ist leer."] };
  const header = rows[0].map((h) => h.trim().toLowerCase());
  const index = CSV_COLUMNS.map((c) => header.indexOf(c));
  if (index.some((i) => i < 0)) return { entries: [], errors: [`Kopfzeile muss diese Spalten enthalten: ${CSV_COLUMNS.join(", ")}`] };
  const entries: LogbookEntry[] = [];
  const errors: string[] = [];
  rows.slice(1).forEach((r, n) => {
    const line = n + 2;
    const [date, status, airline, flightNumber, origin, destination, aircraftTypeId, notes] = index.map((i) => (r[i] ?? "").trim());
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(date))) return void errors.push(`Zeile ${line}: ungültiges Datum „${date}“.`);
    if (status !== "flown" && status !== "planned") return void errors.push(`Zeile ${line}: Status muss flown oder planned sein.`);
    for (const code of [origin, destination]) {
      if (code && !/^[A-Za-z]{3}$/.test(code)) return void errors.push(`Zeile ${line}: „${code}“ ist kein IATA-Flughafencode.`);
    }
    if (!knownAircraft(aircraftTypeId)) return void errors.push(`Zeile ${line}: Flugzeugtyp „${aircraftTypeId}“ ist nicht im Katalog.`);
    entries.push({
      id: `${Date.now()}-${line}-${Math.random().toString(36).slice(2, 8)}`,
      date,
      status,
      airline: airline.toUpperCase().slice(0, 2),
      flightNumber: flightNumber.toUpperCase().slice(0, 8),
      origin: origin.toUpperCase(),
      destination: destination.toUpperCase(),
      aircraftTypeId,
      notes: notes.slice(0, 200),
    });
  });
  return { entries, errors };
}

/** Gleiche Einträge (Datum, Flug, Strecke, Typ, Status) nicht doppelt importieren. */
export function mergeLogbook(existing: LogbookEntry[], imported: LogbookEntry[]): { merged: LogbookEntry[]; added: number } {
  const key = (e: LogbookEntry) => [e.date, e.status, e.airline, e.flightNumber, e.origin, e.destination, e.aircraftTypeId].join("|");
  const seen = new Set(existing.map(key));
  const fresh = imported.filter((e) => {
    const k = key(e);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  return { merged: [...existing, ...fresh].sort((a, b) => b.date.localeCompare(a.date)), added: fresh.length };
}

// ---------- Kalender (.ics) ----------

export interface CalendarFlight {
  uid: string;
  carrier: string;
  flightNumber: string;
  origin: string;
  destination: string;
  departureUtc: string | null;
  arrivalUtc: string | null;
  departureLocal: string;
  arrivalLocal: string;
  aircraft: string | null;
  aircraftStatus: string;
}

const STATUS_TEXT: Record<string, string> = {
  confirmed: "bestätigt",
  scheduled: "geplant, nicht garantiert",
  historical: "historisch beobachtet, nicht garantiert",
};

function icsText(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\n/g, "\\n");
}

const encoder = new TextEncoder();

/** RFC 5545: höchstens 75 Oktette pro Zeile; Folgezeilen beginnen mit Leerzeichen; Zeichen werden nie zerteilt. */
export function foldLine(line: string): string[] {
  const out: string[] = [];
  let current = "";
  let size = 0;
  for (const ch of line) {
    const n = encoder.encode(ch).length;
    if (size + n > 75) {
      out.push(current);
      current = " ";
      size = 1;
    }
    current += ch;
    size += n;
  }
  out.push(current);
  return out;
}

function icsUtc(value: string): string {
  return new Date(value).toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
}

function icsLocal(value: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/.exec(value);
  if (!m) throw new Error(`Ungültige Ortszeit: ${value}`);
  return `${m[1]}${m[2]}${m[3]}T${m[4]}${m[5]}${m[6] ?? "00"}`;
}

export function buildIcs(calendarName: string, flights: CalendarFlight[], now: Date = new Date()): string {
  const stamp = icsUtc(now.toISOString());
  const lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Aviation Trip Optimizer//DE", "CALSCALE:GREGORIAN", "METHOD:PUBLISH", `X-WR-CALNAME:${icsText(calendarName)}`];
  for (const f of flights) {
    const number = f.flightNumber ? ` ${f.flightNumber}` : "";
    const aircraft = f.aircraft ? `Flugzeug: ${f.aircraft} (${STATUS_TEXT[f.aircraftStatus] ?? "Status unbekannt"})` : "Flugzeug: unbekannt";
    const description = `${aircraft}\nNicht gebucht – Vorschlag aus Aviation Trip Optimizer. Vor der Buchung Zeiten und Flugzeugtyp bei der Airline prüfen.`;
    const utc = f.departureUtc && f.arrivalUtc;
    const start = utc ? icsUtc(f.departureUtc!) : icsLocal(f.departureLocal);
    const end = utc ? icsUtc(f.arrivalUtc!) : icsLocal(f.arrivalLocal);
    lines.push(
      "BEGIN:VEVENT",
      `UID:${f.uid}@aviation-trip-optimizer`,
      `DTSTAMP:${stamp}`,
      `DTSTART:${start}`,
      `DTEND:${end}`,
      `SUMMARY:${icsText(`✈ ${f.carrier}${number} ${f.origin} → ${f.destination}`)}`,
      `LOCATION:${icsText(f.origin)}`,
      `DESCRIPTION:${icsText(description)}`,
      "END:VEVENT",
    );
  }
  lines.push("END:VCALENDAR");
  return lines.flatMap(foldLine).map((l) => `${l}\r\n`).join("");
}

export function itineraryCalendar(it: ScoredItinerary["itinerary"]): { name: string; flights: CalendarFlight[] } {
  const flights: CalendarFlight[] = [];
  it.legs.forEach((leg, l) =>
    leg.segments.forEach((s, i) =>
      flights.push({
        uid: `${it.id}-${l}-${i}`,
        carrier: s.marketing_carrier,
        flightNumber: s.flight_number,
        origin: s.origin,
        destination: s.destination,
        departureUtc: s.departure_utc ?? null,
        arrivalUtc: s.arrival_utc ?? null,
        departureLocal: s.departure_local,
        arrivalLocal: s.arrival_local,
        aircraft: s.aircraft_type_id ? aircraftName(s.aircraft_type_id) : (s.aircraft_name ?? null),
        aircraftStatus: s.aircraft_status ?? "unknown",
      }),
    ),
  );
  return { name: `Reise ${it.origin} → ${it.destination} (nicht gebucht)`, flights };
}

/** Bietet Text als Datei zum Speichern an (normale Website, kein Sandbox-iframe). */
export function downloadText(filename: string, mime: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: `${mime};charset=utf-8` }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
