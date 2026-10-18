import type { ScoredItinerary } from "./types";

export type SortKey = "score" | "price" | "duration" | "stay" | "aircraft";

export interface ResultFilter {
  nonstopOnly: boolean;
  wishlistOnly: boolean;
  maxPrice: number | null;
  airline: string;
}

export const DEFAULT_FILTER: ResultFilter = { nonstopOnly: false, wishlistOnly: false, maxPrice: null, airline: "" };

export function filterAndSort(items: ScoredItinerary[], filter: ResultFilter, sort: SortKey): ScoredItinerary[] {
  const filtered = items.filter((s) => {
    const it = s.itinerary;
    if (filter.nonstopOnly && it.stops > 0) return false;
    if (filter.wishlistOnly && s.wishlist_hits.length === 0) return false;
    if (filter.maxPrice !== null && it.price.total > filter.maxPrice) return false;
    if (filter.airline && !it.airlines.includes(filter.airline)) return false;
    return true;
  });
  const aircraftScore = (s: ScoredItinerary) => s.components.find((c) => c.key === "aircraft")?.score ?? 0;
  const sorters: Record<SortKey, (a: ScoredItinerary, b: ScoredItinerary) => number> = {
    score: (a, b) => a.rank - b.rank,
    price: (a, b) => a.itinerary.price.total - b.itinerary.price.total,
    duration: (a, b) => a.itinerary.total_travel_minutes - b.itinerary.total_travel_minutes,
    stay: (a, b) => b.itinerary.full_stay_days - a.itinerary.full_stay_days,
    aircraft: (a, b) => aircraftScore(b) - aircraftScore(a),
  };
  return [...filtered].sort((a, b) => sorters[sort](a, b) || a.rank - b.rank);
}
