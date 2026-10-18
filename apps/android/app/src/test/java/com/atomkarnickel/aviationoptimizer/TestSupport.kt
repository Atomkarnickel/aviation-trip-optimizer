package com.atomkarnickel.aviationoptimizer

import com.atomkarnickel.aviationoptimizer.data.AircraftCatalog
import com.atomkarnickel.aviationoptimizer.data.ApiResult
import com.atomkarnickel.aviationoptimizer.data.AppError
import com.atomkarnickel.aviationoptimizer.data.AppSettings
import com.atomkarnickel.aviationoptimizer.data.FavoriteTrip
import com.atomkarnickel.aviationoptimizer.data.FavoritesStore
import com.atomkarnickel.aviationoptimizer.data.SearchRepository
import com.atomkarnickel.aviationoptimizer.data.SettingsStore
import com.atomkarnickel.aviationoptimizer.data.local.AircraftPreferenceEntity
import com.atomkarnickel.aviationoptimizer.data.remote.AircraftHistoryDto
import com.atomkarnickel.aviationoptimizer.data.remote.AircraftTypeShareDto
import com.atomkarnickel.aviationoptimizer.data.remote.CompareResponseDto
import com.atomkarnickel.aviationoptimizer.data.remote.ExplanationDto
import com.atomkarnickel.aviationoptimizer.data.remote.ItineraryDto
import com.atomkarnickel.aviationoptimizer.data.remote.LegDto
import com.atomkarnickel.aviationoptimizer.data.remote.MetaDto
import com.atomkarnickel.aviationoptimizer.data.remote.PriceDto
import com.atomkarnickel.aviationoptimizer.data.remote.ProviderStatusDto
import com.atomkarnickel.aviationoptimizer.data.remote.ScoreComponentDto
import com.atomkarnickel.aviationoptimizer.data.remote.ScoredItineraryDto
import com.atomkarnickel.aviationoptimizer.data.remote.SearchRequestDto
import com.atomkarnickel.aviationoptimizer.data.remote.SearchResponseDto
import com.atomkarnickel.aviationoptimizer.data.remote.SearchStatsDto
import com.atomkarnickel.aviationoptimizer.data.remote.SegmentDto
import com.atomkarnickel.aviationoptimizer.data.remote.UserPreferencesDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

val testJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

fun sharedFile(name: String): File = File(System.getProperty("sharedDir") ?: "../../../shared", name)

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class FakeSettings(initial: AppSettings = AppSettings(backendUrl = "https://api.example.org")) : SettingsStore {
    val state = MutableStateFlow(initial)
    override val settings = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}

class FakeFavorites : FavoritesStore {
    val tripsState = MutableStateFlow<List<FavoriteTrip>>(emptyList())
    val aircraftState = MutableStateFlow<Map<String, AircraftPreferenceEntity>>(emptyMap())
    override val trips = tripsState
    override val aircraftPreferences = aircraftState
    override suspend fun toggleTrip(searchId: String, item: ScoredItineraryDto) {
        tripsState.value = if (tripsState.value.any { it.itinerary.id == item.itinerary.id }) {
            tripsState.value.filterNot { it.itinerary.id == item.itinerary.id }
        } else tripsState.value + FavoriteTrip(searchId, 0, item.totalScore, item.explanation.summary, item.itinerary)
    }
    override suspend fun removeTrip(itineraryId: String) {
        tripsState.value = tripsState.value.filterNot { it.itinerary.id == itineraryId }
    }
    override suspend fun setAircraft(typeId: String, status: String, rating: Double?) {
        aircraftState.value = aircraftState.value + (typeId to AircraftPreferenceEntity(typeId, status, rating))
    }
}

class FakeSearchRepository : SearchRepository {
    var metaResult: ApiResult<MetaDto> = ApiResult.Success(
        MetaDto("Aviation Trip Optimizer", "1.0.0", ProviderStatusDto("duffel", true, "live", "Live")),
    )
    var searchResult: ApiResult<SearchResponseDto> = ApiResult.Success(sampleResponse())
    var rescoreResult: ApiResult<SearchResponseDto> = ApiResult.Success(sampleResponse(mode = "cheapest"))
    var gate: CompletableDeferred<Unit>? = null
    val searchRequests = mutableListOf<SearchRequestDto>()
    val rescorePrefs = mutableListOf<UserPreferencesDto>()

    override suspend fun meta() = metaResult
    override suspend fun search(request: SearchRequestDto): ApiResult<SearchResponseDto> {
        searchRequests += request
        gate?.await()
        return searchResult
    }
    override suspend fun rescore(searchId: String, preferences: UserPreferencesDto): ApiResult<SearchResponseDto> {
        rescorePrefs += preferences
        return rescoreResult
    }
    override suspend fun compare(searchId: String, ids: List<String>): ApiResult<CompareResponseDto> =
        ApiResult.Failure(AppError(AppError.Kind.NOT_FOUND, "nicht verwendet"))

    var historyCalls = 0
    var historyResult: ApiResult<AircraftHistoryDto> = ApiResult.Success(
        AircraftHistoryDto("ok", "LX 38", "SWR38", "ZRH", 5,
            distribution = listOf(AircraftTypeShareDto("A343", "Airbus A340-300", 4, 0.8))),
    )
    override suspend fun aircraftHistory(carrier: String, flightNumber: String, origin: String): ApiResult<AircraftHistoryDto> {
        historyCalls++
        return historyResult
    }
}

val emptyCatalog = AircraftCatalog("test", "2026-10-18", emptyList())

fun sampleItem(id: String, rank: Int, price: Double, stops: Int = 0, wishlist: List<String> = emptyList(), aircraftScore: Double = 0.5) =
    ScoredItineraryDto(
        rank = rank,
        itinerary = ItineraryDto(
            id = id, provider = "duffel", priceSource = "live_offer", origin = "ZRH", destination = "SIN",
            legs = listOf(
                LegDto("outbound", listOf(SegmentDto("SQ", "Singapore Airlines", "SQ", "345", "ZRH", "SIN", "2026-12-18T11:25:00", "2026-12-19T06:15:00", 710, null, "A380", "scheduled")), emptyList(), 710),
            ),
            price = PriceDto(price, "EUR", null, null, price, 1), outboundDate = "2026-12-18", returnDate = "2027-01-02",
            nights = 14, fullStayDays = 13, totalTravelMinutes = 1400 + stops * 100, stops = stops, airlines = listOf("SQ"),
            aircraftTypeIds = listOf("A380"), fetchedAt = "2026-10-18T10:00:00Z",
        ),
        totalScore = 90.0 - rank, components = listOf(ScoreComponentDto("aircraft", "Flugzeugerlebnis", aircraftScore, 4.0, 10.0, "")),
        wishlistHits = wishlist, extraCostVsCheapest = 0.0, explanation = ExplanationDto("Zusammenfassung $id", emptyList(), emptyList(), emptyList()),
    )

fun sampleResponse(mode: String = "best_value") = SearchResponseDto(
    searchId = "s1", status = "ok", provider = ProviderStatusDto("duffel", true, "live", "Live"), isDemo = false, mode = mode,
    results = listOf(sampleItem("a", 1, 900.0), sampleItem("b", 2, 700.0, stops = 1, wishlist = listOf("A380"), aircraftScore = 0.9), sampleItem("c", 3, 1200.0)),
    cheapestTotal = 700.0, stats = SearchStatsDto(10, 6, 30),
)
