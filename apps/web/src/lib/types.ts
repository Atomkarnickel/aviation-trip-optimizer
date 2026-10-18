// Bequeme Aliase auf die aus shared/openapi.json generierten Typen (npm run gen:api).
import type { components } from "./api-types";

type S = components["schemas"];
export type SearchRequest = S["SearchRequest"];
export type SearchResponse = S["SearchResponse"];
export type ScoredItinerary = S["ScoredItinerary"];
export type Itinerary = S["Itinerary"];
export type Leg = S["Leg"];
export type Segment = S["Segment"];
export type AircraftType = S["AircraftType"];
export type Airport = S["Airport"];
export type Airline = S["Airline"];
export type UserPreferences = S["UserPreferences"];
export type Weights = S["Weights"];
export type AircraftPreference = S["AircraftPreference"];
export type CompareResponse = S["CompareResponse"];
export type MetaResponse = S["MetaResponse"];
export type ProviderStatus = S["ProviderStatus"];
export type OptimizationMode = S["OptimizationMode"];
export type AircraftPreferenceStatus = S["AircraftPreferenceStatus"];
