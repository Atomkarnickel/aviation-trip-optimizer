package com.atomkarnickel.aviationoptimizer.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Teilmenge der Backend-Schemas (shared/openapi.json). Unbekannte Felder werden ignoriert.

@Serializable
data class WeightsDto(
    val price: Double = 5.0,
    val aircraft: Double = 4.0,
    @SerialName("travel_time") val travelTime: Double = 3.0,
    val stops: Double = 3.0,
    val stay: Double = 3.0,
    val airline: Double = 1.0,
    val comfort: Double = 2.0,
)

@Serializable
data class AircraftPreferenceDto(
    @SerialName("aircraft_type_id") val aircraftTypeId: String,
    val status: String,
    @SerialName("personal_rating") val personalRating: Double? = null,
)

@Serializable
data class UserPreferencesDto(
    val mode: String = "best_value",
    val weights: WeightsDto? = null,
    @SerialName("aircraft_preferences") val aircraftPreferences: List<AircraftPreferenceDto> = emptyList(),
    @SerialName("preferred_airlines") val preferredAirlines: List<String> = emptyList(),
    @SerialName("flown_factor") val flownFactor: Double = 0.5,
)

@Serializable
data class SearchRequestDto(
    val origins: List<String>,
    val destinations: List<String>,
    @SerialName("earliest_departure") val earliestDeparture: String,
    @SerialName("latest_return") val latestReturn: String,
    @SerialName("min_stay_days") val minStayDays: Int,
    @SerialName("max_stay_days") val maxStayDays: Int,
    val adults: Int,
    @SerialName("cabin_class") val cabinClass: String,
    val currency: String = "EUR",
    @SerialName("max_budget") val maxBudget: Double? = null,
    @SerialName("desired_aircraft") val desiredAircraft: List<String> = emptyList(),
    @SerialName("preferred_airlines") val preferredAirlines: List<String> = emptyList(),
    @SerialName("excluded_airlines") val excludedAirlines: List<String> = emptyList(),
    @SerialName("max_stops") val maxStops: Int = 1,
    @SerialName("max_travel_hours") val maxTravelHours: Double? = null,
    @SerialName("require_checked_bag") val requireCheckedBag: Boolean = false,
    @SerialName("max_date_combinations") val maxDateCombinations: Int = 6,
    val preferences: UserPreferencesDto = UserPreferencesDto(),
)

@Serializable
data class ProviderStatusDto(val name: String, val configured: Boolean, val mode: String, val message: String)

@Serializable
data class MetaDto(
    @SerialName("app_name") val appName: String,
    @SerialName("backend_version") val backendVersion: String,
    val provider: ProviderStatusDto,
)

@Serializable
data class SegmentDto(
    @SerialName("marketing_carrier") val marketingCarrier: String,
    @SerialName("marketing_carrier_name") val marketingCarrierName: String? = null,
    @SerialName("operating_carrier") val operatingCarrier: String? = null,
    @SerialName("flight_number") val flightNumber: String,
    val origin: String,
    val destination: String,
    @SerialName("departure_local") val departureLocal: String,
    @SerialName("arrival_local") val arrivalLocal: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    @SerialName("aircraft_name") val aircraftName: String? = null,
    @SerialName("aircraft_type_id") val aircraftTypeId: String? = null,
    @SerialName("aircraft_status") val aircraftStatus: String = "unknown",
)

@Serializable
data class ConnectionDto(
    val airport: String,
    val minutes: Int,
    val risk: String,
    val note: String? = null,
    @SerialName("airport_change") val airportChange: Boolean = false,
    val overnight: Boolean = false,
)

@Serializable
data class LegDto(
    val direction: String,
    val segments: List<SegmentDto>,
    val connections: List<ConnectionDto> = emptyList(),
    @SerialName("duration_minutes") val durationMinutes: Int,
    @SerialName("details_complete") val detailsComplete: Boolean = true,
    @SerialName("reported_stops") val reportedStops: Int? = null,
)

@Serializable
data class PriceDto(
    val total: Double,
    val currency: String,
    val base: Double? = null,
    val taxes: Double? = null,
    @SerialName("per_traveler") val perTraveler: Double,
    val travelers: Int,
    @SerialName("baggage_note") val baggageNote: String? = null,
)

@Serializable
data class BookingOptionDto(val kind: String, val label: String, val url: String, @SerialName("is_deep_link") val isDeepLink: Boolean = false)

@Serializable
data class ItineraryDto(
    val id: String,
    val provider: String,
    @SerialName("price_source") val priceSource: String,
    @SerialName("is_demo") val isDemo: Boolean = false,
    val origin: String,
    val destination: String,
    val legs: List<LegDto>,
    val price: PriceDto,
    @SerialName("booking_options") val bookingOptions: List<BookingOptionDto> = emptyList(),
    @SerialName("outbound_date") val outboundDate: String,
    @SerialName("return_date") val returnDate: String,
    val nights: Int,
    @SerialName("full_stay_days") val fullStayDays: Int,
    @SerialName("total_travel_minutes") val totalTravelMinutes: Int,
    val stops: Int,
    val airlines: List<String>,
    @SerialName("aircraft_type_ids") val aircraftTypeIds: List<String>,
    @SerialName("fetched_at") val fetchedAt: String,
    @SerialName("offer_expires_at") val offerExpiresAt: String? = null,
    @SerialName("data_notes") val dataNotes: List<String> = emptyList(),
)

@Serializable
data class ScoreComponentDto(val key: String, val label: String, val score: Double, val weight: Double, val contribution: Double, val detail: String)

@Serializable
data class ExplanationDto(
    val summary: String,
    val reasons: List<String>,
    val tradeoffs: List<String>,
    val uncertainties: List<String>,
    @SerialName("better_value_alternative_id") val betterValueAlternativeId: String? = null,
    @SerialName("better_value_note") val betterValueNote: String? = null,
)

@Serializable
data class ScoredItineraryDto(
    val rank: Int,
    val itinerary: ItineraryDto,
    @SerialName("total_score") val totalScore: Double,
    val components: List<ScoreComponentDto>,
    @SerialName("wishlist_hits") val wishlistHits: List<String>,
    @SerialName("extra_cost_vs_cheapest") val extraCostVsCheapest: Double,
    @SerialName("aircraft_premium") val aircraftPremium: Double? = null,
    val explanation: ExplanationDto,
)

@Serializable
data class SearchStatsDto(
    @SerialName("combinations_total") val combinationsTotal: Int,
    @SerialName("combinations_searched") val combinationsSearched: Int,
    @SerialName("offers_received") val offersReceived: Int,
)

@Serializable
data class SearchResponseDto(
    @SerialName("search_id") val searchId: String,
    val status: String,
    val provider: ProviderStatusDto,
    @SerialName("is_demo") val isDemo: Boolean,
    val cached: Boolean = false,
    val mode: String,
    val results: List<ScoredItineraryDto>,
    @SerialName("cheapest_total") val cheapestTotal: Double? = null,
    val stats: SearchStatsDto,
    val calendar: List<CalendarCellDto> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CalendarCellDto(
    @SerialName("departure_date") val departureDate: String,
    val nights: Int,
    @SerialName("min_price") val minPrice: Double,
    val currency: String,
    val offers: Int,
    @SerialName("itinerary_id") val itineraryId: String,
)

@Serializable
data class AircraftTypeShareDto(
    @SerialName("aircraft_type_id") val aircraftTypeId: String? = null,
    val name: String,
    val count: Int,
    val share: Double,
)

@Serializable
data class AircraftObservationDto(
    val date: String,
    val callsign: String,
    val icao24: String,
    val registration: String? = null,
    @SerialName("type_code") val typeCode: String? = null,
)

@Serializable
data class AircraftHistoryDto(
    val status: String,
    val flight: String,
    val callsign: String? = null,
    val origin: String,
    @SerialName("days_checked") val daysChecked: Int,
    val observations: List<AircraftObservationDto> = emptyList(),
    val distribution: List<AircraftTypeShareDto> = emptyList(),
    val source: String = "",
    val message: String? = null,
)

@Serializable
data class RescoreRequestDto(val preferences: UserPreferencesDto)

@Serializable
data class CompareRequestDto(@SerialName("itinerary_ids") val itineraryIds: List<String>)

@Serializable
data class CompareRowDto(val label: String, val values: List<String>, @SerialName("best_index") val bestIndex: Int? = null)

@Serializable
data class CompareResponseDto(@SerialName("itinerary_ids") val itineraryIds: List<String>, val rows: List<CompareRowDto>)

@Serializable
data class ValidationErrorDto(val detail: String? = null, val errors: List<FieldErrorDto> = emptyList())

@Serializable
data class FieldErrorDto(val field: String, val message: String)

// ---------------------------------------------------------------- GitHub Releases API

@Serializable
data class GitHubAssetDto(
    val name: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    val size: Long? = null,
)

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val body: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

// ---------------------------------------------------------------- Flugzeugkatalog (Asset)

@Serializable
data class AircraftTypeDto(
    val id: String,
    val manufacturer: String,
    val model: String,
    val family: String,
    @SerialName("typical_seats") val typicalSeats: String,
    @SerialName("typical_use") val typicalUse: String,
    val status: String,
    @SerialName("default_coolness") val defaultCoolness: Double,
    val notes: String = "",
) {
    val displayName: String get() = if (model.startsWith(manufacturer)) model else "$manufacturer $model"
}

@Serializable
data class AircraftCatalogDto(val source: String, val updated: String, val types: List<AircraftTypeDto>)
