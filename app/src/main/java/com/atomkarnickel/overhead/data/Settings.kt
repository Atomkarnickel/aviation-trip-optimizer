package com.atomkarnickel.overhead.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.atomkarnickel.overhead.domain.AltitudeUnit
import com.atomkarnickel.overhead.domain.LatLon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class LocationMode { DEVICE, FIXED }

@Serializable
data class SavedLocation(val position: LatLon, val timeMillis: Long)

@Serializable
data class Settings(
    val radiusKm: Int = 25,
    val locationMode: LocationMode = LocationMode.DEVICE,
    val fixedLocation: LatLon? = null,
    /** Letzter bekannter Gerätestandort – Rückfall für das Widget, wenn im Hintergrund kein Standort verfügbar ist. */
    val lastDeviceLocation: SavedLocation? = null,
    val hideGround: Boolean = true,
    val altitudeUnit: AltitudeUnit = AltitudeUnit.METERS,
    /** Widget-Aktualisierung in Minuten; 0 = nur manuell. Android erlaubt periodisch minimal 15 min. */
    val widgetIntervalMinutes: Int = 15,
) {
    companion object {
        val RADIUS_OPTIONS = listOf(10, 25, 50, 100)
        val WIDGET_INTERVAL_OPTIONS = listOf(15, 30, 60, 0)
    }
}

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}

/** Einstellungen als JSON in DataStore – robust gegen neue Felder (Standardwerte). */
class DataStoreSettings(private val store: DataStore<Preferences>) : SettingsRepository {
    private val key = stringPreferencesKey("settings_v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val settings: Flow<Settings> = store.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val current = prefs[key]?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()
            prefs[key] = json.encodeToString(Settings.serializer(), transform(current))
        }
    }
}
