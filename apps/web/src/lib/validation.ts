// Clientseitige Validierung – spiegelt die Regeln aus backend/app/models/schemas.py (SearchRequest).

export interface SearchFormValues {
  origins: string;
  destinations: string;
  earliestDeparture: string;
  latestReturn: string;
  minStay: number;
  maxStay: number;
  adults: number;
  maxBudget: string;
  maxStops: number;
  maxTravelHours: string;
  desiredAircraft: string[];
  cabinClass: "economy" | "premium_economy" | "business" | "first";
  requireCheckedBag: boolean;
  outboundEarliestHour: string;
  outboundLatestHour: string;
  maxDateCombinations: number;
}

export type FieldErrors = Partial<Record<keyof SearchFormValues, string>>;

const AIRPORT = /^[A-Z]{3}$/;

export function parseCodes(value: string): string[] {
  return Array.from(
    new Set(
      value
        .split(/[\s,;]+/)
        .map((v) => v.trim().toUpperCase())
        .filter(Boolean),
    ),
  );
}

function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(b) - Date.parse(a)) / 86400000);
}

export function validateSearch(v: SearchFormValues): FieldErrors {
  const e: FieldErrors = {};
  const origins = parseCodes(v.origins);
  const destinations = parseCodes(v.destinations);
  if (origins.length === 0) e.origins = "Mindestens einen Abflughafen angeben (IATA-Code, z. B. ZRH).";
  else if (origins.length > 5) e.origins = "Höchstens 5 Abflughäfen.";
  else if (origins.some((c) => !AIRPORT.test(c))) e.origins = "Ungültiger IATA-Code (3 Buchstaben).";
  if (destinations.length > 10) e.destinations = "Höchstens 10 Ziele.";
  else if (destinations.some((c) => !AIRPORT.test(c))) e.destinations = "Ungültiger IATA-Code (3 Buchstaben).";
  else if (destinations.some((d) => origins.includes(d))) e.destinations = "Ziel darf nicht Abflughafen sein.";

  if (!v.earliestDeparture) e.earliestDeparture = "Frühesten Abflug angeben.";
  if (!v.latestReturn) e.latestReturn = "Spätesten Rückflug angeben.";
  if (v.earliestDeparture && v.latestReturn) {
    const span = daysBetween(v.earliestDeparture, v.latestReturn);
    if (span <= 0) e.latestReturn = "Rückflug muss nach dem Abflug liegen.";
    else if (span > 120) e.latestReturn = "Zeitraum höchstens 120 Tage.";
    else if (v.minStay > span) e.minStay = "Minimale Reisedauer passt nicht in den Zeitraum.";
  }
  if (!Number.isInteger(v.minStay) || v.minStay < 1 || v.minStay > 60) e.minStay = "1–60 Tage.";
  if (!Number.isInteger(v.maxStay) || v.maxStay < 1 || v.maxStay > 60) e.maxStay = "1–60 Tage.";
  else if (v.minStay > v.maxStay) e.maxStay = "Maximum muss ≥ Minimum sein.";
  if (!Number.isInteger(v.adults) || v.adults < 1 || v.adults > 9) e.adults = "1–9 Reisende.";
  if (v.maxBudget && !(Number(v.maxBudget) > 0)) e.maxBudget = "Budget muss eine positive Zahl sein.";
  if (v.maxTravelHours && !(Number(v.maxTravelHours) > 0 && Number(v.maxTravelHours) <= 60)) {
    e.maxTravelHours = "0–60 Stunden.";
  }
  return e;
}

export function toSearchRequest(v: SearchFormValues, extras: { preferences: unknown; preferredAirlines: string[]; excludedAirlines: string[] }) {
  const window =
    v.outboundEarliestHour !== "" || v.outboundLatestHour !== ""
      ? { earliest_hour: Number(v.outboundEarliestHour || 0), latest_hour: Number(v.outboundLatestHour || 23) }
      : null;
  return {
    origins: parseCodes(v.origins),
    destinations: parseCodes(v.destinations),
    earliest_departure: v.earliestDeparture,
    latest_return: v.latestReturn,
    min_stay_days: v.minStay,
    max_stay_days: v.maxStay,
    adults: v.adults,
    cabin_class: v.cabinClass,
    currency: "EUR",
    max_budget: v.maxBudget ? Number(v.maxBudget) : null,
    max_stops: v.maxStops,
    max_travel_hours: v.maxTravelHours ? Number(v.maxTravelHours) : null,
    outbound_departure_window: window,
    desired_aircraft: v.desiredAircraft,
    preferred_airlines: extras.preferredAirlines,
    excluded_airlines: extras.excludedAirlines,
    require_checked_bag: v.requireCheckedBag,
    max_date_combinations: v.maxDateCombinations,
    preferences: extras.preferences,
  };
}
