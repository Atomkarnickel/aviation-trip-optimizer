package com.atomkarnickel.overhead.data

import com.atomkarnickel.overhead.domain.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface RefreshOutcome {
    data class Success(val snapshot: Snapshot) : RefreshOutcome
    /** Weder Gerätestandort noch fester Standort verfügbar. */
    data object NoLocation : RefreshOutcome
    data class Failed(val message: String, val previous: Snapshot?) : RefreshOutcome
}

/**
 * Ein Aktualisierungsdurchlauf – gemeinsam genutzt von App (Live-Ansicht), Hintergrund-Worker und Widget-Knopf.
 * Bei Fehlern bleibt der letzte Stand erhalten und wird mit Fehlerhinweis markiert.
 */
class Refresher(
    private val settings: SettingsRepository,
    private val location: LocationResolver,
    private val repository: AircraftRepository,
    private val store: SnapshotStore,
    private val onSaved: suspend (Snapshot) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun refresh(foreground: Boolean, withRoutes: Boolean = true): RefreshOutcome = withContext(dispatcher) { mutex.withLock { doRefresh(foreground, withRoutes) } }

    private suspend fun doRefresh(foreground: Boolean, withRoutes: Boolean): RefreshOutcome {
        val s = settings.settings.first()
        val observer = location.resolve(s, foreground) ?: return RefreshOutcome.NoLocation
        val previous = store.load()
        return try {
            val snapshot = repository.fetch(observer, s.radiusKm, s.hideGround, withRoutes)
            store.save(snapshot)
            onSaved(snapshot)
            RefreshOutcome.Success(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Unbekannter Fehler"
            previous?.let {
                val marked = it.copy(error = message)
                store.save(marked)
                onSaved(marked)
            }
            RefreshOutcome.Failed(message, previous)
        }
    }
}
