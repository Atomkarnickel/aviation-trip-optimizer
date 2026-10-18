package com.atomkarnickel.aviationoptimizer.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomkarnickel.aviationoptimizer.data.AircraftCatalog
import com.atomkarnickel.aviationoptimizer.data.AppError
import com.atomkarnickel.aviationoptimizer.data.ApiResult
import com.atomkarnickel.aviationoptimizer.data.FavoritesStore
import com.atomkarnickel.aviationoptimizer.data.SearchRepository
import com.atomkarnickel.aviationoptimizer.data.SettingsStore
import com.atomkarnickel.aviationoptimizer.data.buildPreferences
import com.atomkarnickel.aviationoptimizer.data.remote.AircraftHistoryDto
import com.atomkarnickel.aviationoptimizer.data.remote.CompareResponseDto
import com.atomkarnickel.aviationoptimizer.data.remote.ProviderStatusDto
import com.atomkarnickel.aviationoptimizer.data.remote.ScoredItineraryDto
import com.atomkarnickel.aviationoptimizer.data.remote.SearchRequestDto
import com.atomkarnickel.aviationoptimizer.data.remote.SearchResponseDto
import com.atomkarnickel.aviationoptimizer.domain.SearchField
import com.atomkarnickel.aviationoptimizer.domain.SearchForm
import com.atomkarnickel.aviationoptimizer.domain.SearchValidation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface BackendState {
    data object Checking : BackendState
    data class Unavailable(val message: String) : BackendState
    data class Connected(val provider: ProviderStatusDto) : BackendState
}

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Success(val response: SearchResponseDto) : SearchState
    data class Failed(val message: String) : SearchState
}

enum class SortKey(val label: String) { SCORE("Score"), PRICE("Preis"), DURATION("Reisezeit"), STAY("Aufenthalt"), AIRCRAFT("Flugzeug") }

data class ResultFilter(
    val nonstopOnly: Boolean = false,
    val wishlistOnly: Boolean = false,
    val departureDate: String? = null,
    val nights: Int? = null,
)

sealed interface HistoryState {
    data object Loading : HistoryState
    data class Loaded(val history: AircraftHistoryDto) : HistoryState
    data class Failed(val message: String) : HistoryState
}

fun historyKey(carrier: String, flightNumber: String, origin: String) = "$carrier-$flightNumber-$origin"

data class DiscoverUiState(
    val form: SearchForm = SearchForm(),
    val fieldErrors: Map<SearchField, String> = emptyMap(),
    val backend: BackendState = BackendState.Checking,
    val search: SearchState = SearchState.Idle,
    val mode: String = "best_value",
    val customWeights: Boolean = false,
    val sort: SortKey = SortKey.SCORE,
    val filter: ResultFilter = ResultFilter(),
    val favoriteIds: Set<String> = emptySet(),
    val compareIds: List<String> = emptyList(),
    val compare: CompareResponseDto? = null,
    val message: String? = null,
    val history: Map<String, HistoryState> = emptyMap(),
)

fun visibleResults(results: List<ScoredItineraryDto>, filter: ResultFilter, sort: SortKey): List<ScoredItineraryDto> {
    val aircraftScore = { s: ScoredItineraryDto -> s.components.firstOrNull { it.key == "aircraft" }?.score ?: 0.0 }
    val filtered = results.filter {
        (!filter.nonstopOnly || it.itinerary.stops == 0) && (!filter.wishlistOnly || it.wishlistHits.isNotEmpty()) &&
            (filter.departureDate == null || it.itinerary.outboundDate == filter.departureDate) &&
            (filter.nights == null || it.itinerary.nights == filter.nights)
    }
    val comparator: Comparator<ScoredItineraryDto> = when (sort) {
        SortKey.SCORE -> compareBy { it.rank }
        SortKey.PRICE -> compareBy { it.itinerary.price.total }
        SortKey.DURATION -> compareBy { it.itinerary.totalTravelMinutes }
        SortKey.STAY -> compareByDescending { it.itinerary.fullStayDays }
        SortKey.AIRCRAFT -> compareByDescending(aircraftScore)
    }
    return filtered.sortedWith(comparator.thenBy { it.rank })
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val settingsStore: SettingsStore,
    private val favorites: FavoritesStore,
    val catalog: AircraftCatalog,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val wishlist = favorites.aircraftPreferences.first().filterValues { it.status == "wishlist" }.keys
            _state.update {
                it.copy(
                    form = it.form.copy(origins = settings.homeAirports.joinToString(", "), desiredAircraft = wishlist),
                    mode = settings.mode, customWeights = settings.customWeights != null,
                )
            }
        }
        viewModelScope.launch {
            settingsStore.settings.map { it.backendUrl }.distinctUntilChanged().collect { checkBackend() }
        }
        viewModelScope.launch {
            favorites.trips.collect { trips -> _state.update { it.copy(favoriteIds = trips.map { t -> t.itinerary.id }.toSet()) } }
        }
    }

    fun checkBackend() {
        viewModelScope.launch {
            _state.update { it.copy(backend = BackendState.Checking) }
            val result = repository.meta()
            _state.update {
                it.copy(
                    backend = when (result) {
                        is ApiResult.Success -> BackendState.Connected(result.value.provider)
                        is ApiResult.Failure -> BackendState.Unavailable(result.error.message)
                    },
                )
            }
        }
    }

    fun updateForm(transform: (SearchForm) -> SearchForm) {
        _state.update { it.copy(form = transform(it.form), fieldErrors = emptyMap()) }
    }

    fun search() {
        val current = _state.value
        if (current.search is SearchState.Loading) return // Schutz vor Doppel-Anfragen
        val errors = SearchValidation.validate(current.form)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }
        _state.update { it.copy(search = SearchState.Loading, compare = null, compareIds = emptyList(), message = null) }
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val prefs = buildPreferences(settings, favorites.aircraftPreferences.first())
            val f = current.form
            val request = SearchRequestDto(
                origins = SearchValidation.parseCodes(f.origins),
                destinations = SearchValidation.parseCodes(f.destinations),
                earliestDeparture = f.earliestDeparture,
                latestReturn = f.latestReturn,
                minStayDays = f.minStay.toInt(),
                maxStayDays = f.maxStay.toInt(),
                adults = f.adults.toInt(),
                cabinClass = f.cabinClass,
                maxBudget = f.maxBudget.toDoubleOrNull(),
                desiredAircraft = f.desiredAircraft.toList(),
                preferredAirlines = settings.preferredAirlines,
                excludedAirlines = settings.excludedAirlines,
                maxStops = f.maxStops,
                maxTravelHours = f.maxTravelHours.toDoubleOrNull(),
                requireCheckedBag = f.requireCheckedBag,
                maxDateCombinations = f.maxDateCombinations,
                preferences = prefs,
            )
            when (val result = repository.search(request)) {
                is ApiResult.Success -> _state.update { it.copy(search = SearchState.Success(result.value)) }
                is ApiResult.Failure -> _state.update {
                    it.copy(search = SearchState.Failed(result.error.message), fieldErrors = mapFieldErrors(result.error))
                }
            }
        }
    }

    private fun mapFieldErrors(error: AppError): Map<SearchField, String> =
        error.fieldErrors.mapNotNull { (field, msg) -> SearchValidation.fieldFromBackend(field)?.let { it to msg } }.toMap()

    fun changeMode(mode: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(mode = mode, customWeights = null) }
            _state.update { it.copy(mode = mode, customWeights = false) }
            val current = (_state.value.search as? SearchState.Success)?.response ?: return@launch
            if (current.results.isEmpty()) return@launch
            val prefs = buildPreferences(settingsStore.settings.first(), favorites.aircraftPreferences.first())
            when (val result = repository.rescore(current.searchId, prefs)) {
                is ApiResult.Success -> _state.update { it.copy(search = SearchState.Success(result.value)) }
                is ApiResult.Failure -> _state.update { it.copy(message = result.error.message) }
            }
        }
    }

    fun setSort(sort: SortKey) = _state.update { it.copy(sort = sort) }
    fun setFilter(filter: ResultFilter) = _state.update { it.copy(filter = filter) }

    fun toggleFavorite(item: ScoredItineraryDto) {
        val response = (_state.value.search as? SearchState.Success)?.response ?: return
        viewModelScope.launch { favorites.toggleTrip(response.searchId, item) }
    }

    fun toggleCompare(id: String) = _state.update {
        val ids = if (id in it.compareIds) it.compareIds - id else if (it.compareIds.size < 4) it.compareIds + id else it.compareIds
        it.copy(compareIds = ids)
    }

    fun runCompare() {
        val response = (_state.value.search as? SearchState.Success)?.response ?: return
        val ids = _state.value.compareIds
        if (ids.size < 2) return
        viewModelScope.launch {
            when (val result = repository.compare(response.searchId, ids)) {
                is ApiResult.Success -> _state.update { it.copy(compare = result.value) }
                is ApiResult.Failure -> _state.update { it.copy(message = result.error.message) }
            }
        }
    }

    fun closeCompare() = _state.update { it.copy(compare = null) }

    /** Lädt auf Wunsch die beobachteten Flugzeugtypen einer Flugnummer (OpenSky, kostenlos). */
    fun loadHistory(carrier: String, flightNumber: String, origin: String) {
        val key = historyKey(carrier, flightNumber, origin)
        if (_state.value.history[key] is HistoryState.Loading || _state.value.history[key] is HistoryState.Loaded) return
        _state.update { it.copy(history = it.history + (key to HistoryState.Loading)) }
        viewModelScope.launch {
            val next = when (val r = repository.aircraftHistory(carrier, flightNumber, origin)) {
                is ApiResult.Success -> HistoryState.Loaded(r.value)
                is ApiResult.Failure -> HistoryState.Failed(r.error.message)
            }
            _state.update { it.copy(history = it.history + (key to next)) }
        }
    }
    fun dismissMessage() = _state.update { it.copy(message = null) }
}
