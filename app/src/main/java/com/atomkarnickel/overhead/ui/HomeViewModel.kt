package com.atomkarnickel.overhead.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomkarnickel.overhead.data.DeviceLocation
import com.atomkarnickel.overhead.data.LocationMode
import com.atomkarnickel.overhead.data.RefreshOutcome
import com.atomkarnickel.overhead.data.Refresher
import com.atomkarnickel.overhead.data.Settings
import com.atomkarnickel.overhead.data.SettingsRepository
import com.atomkarnickel.overhead.data.SnapshotStore
import com.atomkarnickel.overhead.data.remote.AircraftPhoto
import com.atomkarnickel.overhead.domain.Aircraft
import com.atomkarnickel.overhead.domain.LatLon
import com.atomkarnickel.overhead.domain.ReferenceData
import com.atomkarnickel.overhead.domain.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

sealed interface PhotoState {
    data object Idle : PhotoState
    data object Loading : PhotoState
    data object None : PhotoState
    data object Error : PhotoState
    data class Loaded(val photo: AircraftPhoto, val bytes: ByteArray) : PhotoState
}

data class HomeUiState(
    val snapshot: Snapshot? = null,
    val settings: Settings = Settings(),
    val refreshing: Boolean = false,
    /** Kein Standort verfügbar – Nutzer muss Berechtigung erteilen oder einen festen Standort wählen. */
    val needsLocation: Boolean = false,
    val lastError: String? = null,
    val selectedHex: String? = null,
    val photo: PhotoState = PhotoState.Idle,
) {
    val selected: Aircraft? get() = selectedHex?.let { hex -> snapshot?.aircraft?.firstOrNull { it.hex == hex } }
}

private data class Transient(
    val refreshing: Boolean = false,
    val needsLocation: Boolean = false,
    val lastError: String? = null,
    val selectedHex: String? = null,
    val photo: PhotoState = PhotoState.Idle,
)

class HomeViewModel(
    private val settingsRepo: SettingsRepository,
    private val refresher: Refresher,
    snapshots: SnapshotStore,
    private val deviceLocation: DeviceLocation,
    val reference: ReferenceData,
    private val loadPhoto: suspend (String) -> AircraftPhoto?,
    private val loadImage: suspend (String) -> ByteArray?,
    private val onSettingsChanged: suspend () -> Unit = {},
) : ViewModel() {
    private val transient = MutableStateFlow(Transient())
    private var refreshJob: Job? = null
    private var photoJob: Job? = null

    val state: StateFlow<HomeUiState> = combine(snapshots.latest, settingsRepo.settings, transient) { snap, settings, t ->
        HomeUiState(snap, settings, t.refreshing, t.needsLocation, t.lastError, t.selectedHex, t.photo)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init {
        viewModelScope.launch { snapshots.load() }
    }

    fun hasLocationPermission() = deviceLocation.hasPermission()
    fun hasBackgroundLocationPermission() = deviceLocation.hasBackgroundPermission()

    /** Einmal aktualisieren; läuft bereits ein Abruf, wird kein zweiter gestartet. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        transient.update { it.copy(refreshing = true) }
        val outcome = try {
            refresher.refresh(foreground = true)
        } catch (e: CancellationException) {
            transient.update { it.copy(refreshing = false) }
            throw e
        }
        transient.update {
            when (outcome) {
                is RefreshOutcome.Success -> it.copy(refreshing = false, needsLocation = false, lastError = null)
                RefreshOutcome.NoLocation -> it.copy(refreshing = false, needsLocation = true)
                is RefreshOutcome.Failed -> it.copy(refreshing = false, needsLocation = false, lastError = outcome.message)
            }
        }
    }

    /** Live-Ansicht: solange die App sichtbar ist, alle [intervalMs] neu laden (vom UI per repeatOnLifecycle gestartet). */
    suspend fun liveLoop(intervalMs: Long = LIVE_INTERVAL_MS) {
        while (coroutineContext.isActive) {
            refreshNow()
            delay(intervalMs)
        }
    }

    fun select(hex: String?) {
        if (hex == transient.value.selectedHex) return
        photoJob?.cancel()
        transient.update { it.copy(selectedHex = hex, photo = if (hex == null) PhotoState.Idle else PhotoState.Loading) }
        if (hex == null) return
        photoJob = viewModelScope.launch {
            val result = try {
                val photo = loadPhoto(hex)
                if (photo == null) PhotoState.None
                else loadImage(photo.imageUrl)?.let { PhotoState.Loaded(photo, it) } ?: PhotoState.Error
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PhotoState.Error
            }
            transient.update { if (it.selectedHex == hex) it.copy(photo = result) else it }
        }
    }

    fun setFixedLocation(position: LatLon) = changeSettings { it.copy(locationMode = LocationMode.FIXED, fixedLocation = position) }

    fun useDeviceLocation() = changeSettings { it.copy(locationMode = LocationMode.DEVICE) }

    fun updateSettings(transform: (Settings) -> Settings) = changeSettings(transform)

    private fun changeSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsRepo.update(transform)
            onSettingsChanged()
            refreshJob?.cancel()
            refreshJob = launch { refreshNow() }
        }
    }

    companion object {
        const val LIVE_INTERVAL_MS = 10_000L
    }
}
