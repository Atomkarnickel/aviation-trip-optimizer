import catalog from "@/generated/aircraft_types.json";

export interface CatalogAircraft {
  id: string;
  manufacturer: string;
  model: string;
  family: string;
  iata_codes: string[];
  body: string;
  engines: number;
  typical_seats: string;
  typical_use: string;
  status: "active" | "rare" | "historical" | string;
  default_coolness: number;
  notes: string;
}

export const AIRCRAFT_SOURCE: { source: string; updated: string } = { source: catalog.source, updated: catalog.updated };
export const AIRCRAFT: CatalogAircraft[] = [...(catalog.types as CatalogAircraft[])].sort(
  (a, b) => b.default_coolness - a.default_coolness || a.id.localeCompare(b.id),
);
const BY_ID = new Map(AIRCRAFT.map((a) => [a.id, a]));

export function aircraftName(id: string | null | undefined): string {
  if (!id) return "Typ unbekannt";
  const a = BY_ID.get(id);
  if (!a) return id;
  return a.model.startsWith(a.manufacturer) ? a.model : `${a.manufacturer} ${a.model}`;
}

export function aircraftById(id: string): CatalogAircraft | undefined {
  return BY_ID.get(id);
}

export const STATUS_LABEL: Record<string, string> = {
  active: "aktiv",
  rare: "selten",
  historical: "historisch – nicht regulär buchbar",
};
