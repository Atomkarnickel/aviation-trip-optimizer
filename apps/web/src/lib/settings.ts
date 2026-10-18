"use client";

import { useCallback, useEffect, useState } from "react";
import { DEFAULT_API_BASE_URL } from "./config";
import { readJson, writeJson } from "./storage";
import type { AircraftPreferenceStatus, Itinerary, OptimizationMode, ScoredItinerary, Weights } from "./types";

export interface AppSettings {
  apiBaseUrl: string;
  mode: OptimizationMode;
  customWeights: Weights | null;
  aircraftStatus: Record<string, AircraftPreferenceStatus>;
  aircraftRating: Record<string, number>;
  preferredAirlines: string[];
  excludedAirlines: string[];
  flownFactor: number;
  homeAirports: string[];
}

export const DEFAULT_SETTINGS: AppSettings = {
  apiBaseUrl: DEFAULT_API_BASE_URL,
  mode: "best_value",
  customWeights: null,
  aircraftStatus: { A380: "wishlist", B748: "wishlist", B744: "wishlist", A346: "wishlist" },
  aircraftRating: {},
  preferredAirlines: [],
  excludedAirlines: [],
  flownFactor: 0.5,
  homeAirports: ["ZRH"],
};

export interface FavoriteTrip {
  savedAt: string;
  searchId: string;
  score: number;
  itinerary: Itinerary;
  summary: string;
}

export interface LogbookEntry {
  id: string;
  date: string;
  flightNumber: string;
  origin: string;
  destination: string;
  aircraftTypeId: string;
  airline: string;
  status: "flown" | "planned";
  notes: string;
}

const KEYS = { settings: "ato.settings.v1", favorites: "ato.favorites.v1", logbook: "ato.logbook.v1" };

function usePersistent<T>(key: string, initial: T): [T, (next: T | ((prev: T) => T)) => void, boolean] {
  const [value, setValue] = useState<T>(initial);
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    const stored = readJson<T>(key);
    if (stored !== null) {
      setValue((prev) =>
        typeof prev === "object" && prev !== null && !Array.isArray(prev) ? { ...prev, ...stored } : stored,
      );
    }
    setLoaded(true);
  }, [key]);
  const update = useCallback(
    (next: T | ((prev: T) => T)) => {
      setValue((prev) => {
        const resolved = typeof next === "function" ? (next as (p: T) => T)(prev) : next;
        writeJson(key, resolved);
        return resolved;
      });
    },
    [key],
  );
  return [value, update, loaded];
}

export function useSettings() {
  return usePersistent<AppSettings>(KEYS.settings, DEFAULT_SETTINGS);
}

export function useFavorites() {
  const [favorites, setFavorites, loaded] = usePersistent<FavoriteTrip[]>(KEYS.favorites, []);
  const isFavorite = (id: string) => favorites.some((f) => f.itinerary.id === id);
  const toggle = (searchId: string, item: ScoredItinerary) =>
    setFavorites((prev) =>
      prev.some((f) => f.itinerary.id === item.itinerary.id)
        ? prev.filter((f) => f.itinerary.id !== item.itinerary.id)
        : [
            {
              savedAt: new Date().toISOString(),
              searchId,
              score: item.total_score,
              itinerary: item.itinerary,
              summary: item.explanation.summary,
            },
            ...prev,
          ].slice(0, 200),
    );
  const remove = (id: string) => setFavorites((prev) => prev.filter((f) => f.itinerary.id !== id));
  return { favorites, isFavorite, toggle, remove, loaded };
}

export function useLogbook() {
  return usePersistent<LogbookEntry[]>(KEYS.logbook, []);
}

export function toPreferences(settings: AppSettings) {
  return {
    mode: settings.mode,
    weights: settings.customWeights,
    flown_factor: settings.flownFactor,
    preferred_airlines: settings.preferredAirlines,
    aircraft_preferences: Object.entries(settings.aircraftStatus)
      .filter(([, status]) => status !== "neutral")
      .map(([id, status]) => ({
        aircraft_type_id: id,
        status,
        personal_rating: settings.aircraftRating[id] ?? null,
      }))
      .concat(
        Object.entries(settings.aircraftRating)
          .filter(([id]) => !settings.aircraftStatus[id] || settings.aircraftStatus[id] === "neutral")
          .map(([id, rating]) => ({ aircraft_type_id: id, status: "neutral" as const, personal_rating: rating })),
      ),
  };
}
