export function money(value: number, currency: string): string {
  try {
    return new Intl.NumberFormat("de-CH", { style: "currency", currency, maximumFractionDigits: 0 }).format(value);
  } catch {
    return `${Math.round(value)} ${currency}`;
  }
}

export function duration(minutes: number): string {
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, "0")} min`;
}

export function dateShort(iso: string): string {
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}.${m}.${y}`;
}

export function timeLocal(iso: string): string {
  return iso.slice(11, 16);
}

export function dateTime(iso: string): string {
  try {
    return new Intl.DateTimeFormat("de-CH", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
  } catch {
    return iso;
  }
}

export function bytes(n: number | null): string {
  if (!n) return "–";
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

export const PRICE_SOURCE_LABEL: Record<string, string> = {
  live_offer: "Live-Angebot",
  test_offer: "Testmodus – kein echter Preis",
  estimated: "Geschätzt – zwischengespeicherter Preis (bis ca. 7 Tage alt)",
  demo: "DEMO – fiktive Daten",
};

export const AIRCRAFT_STATUS_LABEL: Record<string, string> = {
  confirmed: "bestätigt",
  scheduled: "geplant",
  historical: "historisch",
  unknown: "unbekannt",
};

export const MODE_LABEL: Record<string, string> = {
  best_value: "Best Value",
  aircraft_hunter: "Aircraft Hunter",
  cheapest: "Cheapest Trip",
};
