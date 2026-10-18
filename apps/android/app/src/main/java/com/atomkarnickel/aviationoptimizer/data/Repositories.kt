package com.atomkarnickel.aviationoptimizer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.atomkarnickel.aviationoptimizer.BuildConfig
import com.atomkarnickel.aviationoptimizer.data.local.AircraftPreferenceEntity
import com.atomkarnickel.aviationoptimizer.data.local.FavoriteDao
import com.atomkarnickel.aviationoptimizer.data.local.FavoriteTripEntity
import com.atomkarnickel.aviationoptimizer.data.local.FlightLogDao
import com.atomkarnickel.aviationoptimizer.data.local.FlightLogEntryEntity
import com.atomkarnickel.aviationoptimizer.data.remote.AircraftCatalogDto
import com.atomkarnickel.aviationoptimizer.data.remote.AircraftPreferenceDto
import com.atomkarnickel.aviationoptimizer.data.remote.AircraftTypeDto
import com.atomkarnickel.aviationoptimizer.data.remote.BackendApi
import com.atomkarnickel.aviationoptimizer.data.remote.CompareRequestDto
import com.atomkarnickel.aviationoptimizer.data.remote.CompareResponseDto
import com.atomkarnickel.aviationoptimizer.data.remote.GitHubApi
import com.atomkarnickel.aviationoptimizer.data.remote.ItineraryDto
import com.atomkarnickel.aviationoptimizer.data.remote.MetaDto
import com.atomkarnickel.aviationoptimizer.data.remote.RescoreRequestDto
import com.atomkarnickel.aviationoptimizer.data.remote.ScoredItineraryDto
import com.atomkarnickel.aviationoptimizer.data.remote.SearchRequestDto
import com.atomkarnickel.aviationoptimizer.data.remote.SearchResponseDto
import com.atomkarnickel.aviationoptimizer.data.remote.UserPreferencesDto
import com.atomkarnickel.aviationoptimizer.data.remote.ValidationErrorDto
import com.atomkarnickel.aviationoptimizer.data.remote.WeightsDto
import com.atomkarnickel.aviationoptimizer.domain.ReleaseCheck
import com.atomkarnickel.aviationoptimizer.domain.ReleaseRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException

// ================================================================ Einstellungen (DataStore)

@Serializable
data class AppSettings(
    val backendUrl: String = BuildConfig.DEFAULT_API_BASE_URL,
    val mode: String = "best_value",
    val customWeights: WeightsDto? = null,
    val homeAirports: List<String> = listOf("ZRH"),
    val preferredAirlines: List<String> = emptyList(),
    val excludedAirlines: List<String> = emptyList(),
    val flownFactor: Double = 0.5,
)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>, private val json: Json) : SettingsStore {
    private val key = stringPreferencesKey("settings_v1")

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = prefs[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
            prefs[key] = json.encodeToString(AppSettings.serializer(), transform(current))
        }
    }
}

// ================================================================ Fehler & Ergebnisse

data class AppError(val kind: Kind, val message: String, val fieldErrors: Map<String, String> = emptyMap()) {
    enum class Kind { NOT_CONFIGURED, NETWORK, TIMEOUT, VALIDATION, RATE_LIMITED, NOT_FOUND, SERVER, INVALID_RESPONSE }
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: AppError) : ApiResult<Nothing>
}

fun isAllowedBackendUrl(url: String): Boolean {
    val parsed = url.trim().toHttpUrlOrNull() ?: return false
    return parsed.scheme == "https" || parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
}

// ================================================================ Backend-Suche

interface SearchRepository {
    suspend fun meta(): ApiResult<MetaDto>
    suspend fun search(request: SearchRequestDto): ApiResult<SearchResponseDto>
    suspend fun rescore(searchId: String, preferences: UserPreferencesDto): ApiResult<SearchResponseDto>
    suspend fun compare(searchId: String, ids: List<String>): ApiResult<CompareResponseDto>
}

class BackendSearchRepository(
    private val settingsStore: SettingsStore,
    private val client: OkHttpClient,
    private val json: Json,
) : SearchRepository {
    private var cached: Pair<String, BackendApi>? = null

    private suspend fun api(): BackendApi? {
        val url = settingsStore.settings.first().backendUrl.trim()
        if (url.isEmpty() || !isAllowedBackendUrl(url)) return null
        val base = if (url.endsWith("/")) url else "$url/"
        cached?.let { if (it.first == base) return it.second }
        val api = Retrofit.Builder().baseUrl(base).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(BackendApi::class.java)
        cached = base to api
        return api
    }

    private suspend fun <T> call(block: suspend (BackendApi) -> T): ApiResult<T> {
        val api = api() ?: return ApiResult.Failure(
            AppError(AppError.Kind.NOT_CONFIGURED, "Kein Backend verbunden. Hinterlege die HTTPS-Adresse im Profil."),
        )
        return try {
            ApiResult.Success(block(api))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            ApiResult.Failure(mapHttp(e, json))
        } catch (e: SocketTimeoutException) {
            ApiResult.Failure(AppError(AppError.Kind.TIMEOUT, "Zeitüberschreitung – das Backend antwortet nicht."))
        } catch (e: IOException) {
            ApiResult.Failure(AppError(AppError.Kind.NETWORK, "Backend nicht erreichbar. Netzwerk oder Adresse prüfen."))
        } catch (e: SerializationException) {
            ApiResult.Failure(AppError(AppError.Kind.INVALID_RESPONSE, "Unerwartete Antwort vom Backend."))
        }
    }

    override suspend fun meta() = call { it.meta() }
    override suspend fun search(request: SearchRequestDto) = call { it.search(request) }
    override suspend fun rescore(searchId: String, preferences: UserPreferencesDto) =
        call { it.rescore(searchId, RescoreRequestDto(preferences)) }

    override suspend fun compare(searchId: String, ids: List<String>) = call { it.compare(searchId, CompareRequestDto(ids)) }

    companion object {
        fun mapHttp(e: HttpException, json: Json): AppError {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val parsed = body?.let { runCatching { json.decodeFromString<ValidationErrorDto>(it) }.getOrNull() }
            return when (e.code()) {
                422 -> AppError(
                    AppError.Kind.VALIDATION,
                    parsed?.errors?.joinToString(" ") { it.message }?.ifBlank { null } ?: parsed?.detail ?: "Ungültige Eingabe.",
                    parsed?.errors?.associate { it.field to it.message } ?: emptyMap(),
                )
                429 -> AppError(AppError.Kind.RATE_LIMITED, "Zu viele Anfragen – bitte kurz warten.")
                404 -> AppError(AppError.Kind.NOT_FOUND, parsed?.detail ?: "Nicht gefunden.")
                else -> AppError(AppError.Kind.SERVER, "Serverfehler (HTTP ${e.code()}). Bitte später erneut versuchen.")
            }
        }
    }
}

// ================================================================ Releases (GitHub)

interface ReleaseRepository {
    suspend fun check(force: Boolean = false): ReleaseCheck
    suspend fun checksum(url: String): String?
}

class GitHubReleaseRepository(
    private val api: GitHubApi,
    private val repository: String = BuildConfig.GITHUB_REPOSITORY,
    private val prefix: String = BuildConfig.APK_PREFIX,
    private val cacheMillis: Long = BuildConfig.RELEASE_CACHE_SECONDS * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReleaseRepository {
    private var cache: ReleaseCheck? = null

    override suspend fun check(force: Boolean): ReleaseCheck {
        val now = clock()
        cache?.let { if (!force && now - it.checkedAtMillis < cacheMillis) return it }
        val parts = repository.split("/")
        if (parts.size != 2) return ReleaseCheck.Unavailable("Kein Repository konfiguriert.", now)
        val result = try {
            val stable = ReleaseRules.stableSorted(api.releases(parts[0], parts[1]), repository, prefix)
            if (stable.isEmpty()) ReleaseCheck.NoRelease("Noch kein stabiles Release mit APK veröffentlicht.", now)
            else ReleaseCheck.Ok(stable.first(), stable, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> ReleaseCheck.NoRelease("Repository oder Releases nicht gefunden.", now)
                403, 429 -> ReleaseCheck.Unavailable("GitHub-API-Limit erreicht – später erneut prüfen.", now)
                else -> ReleaseCheck.Unavailable("GitHub antwortete mit HTTP ${e.code()}.", now)
            }
        } catch (e: IOException) {
            ReleaseCheck.Unavailable("Versionsprüfung nicht möglich (Netzwerkfehler).", now)
        } catch (e: SerializationException) {
            ReleaseCheck.Unavailable("Unerwartete Antwort der GitHub-API.", now)
        }
        if (result is ReleaseCheck.Unavailable) {
            (cache as? ReleaseCheck.Ok)?.let { return it.copy(stale = true) }
            return result
        }
        cache = result
        return result
    }

    override suspend fun checksum(url: String): String? = try {
        if (!url.startsWith("https://github.com/$repository/releases/download/")) null
        else api.text(url).use { body -> if (body.contentLength() > 4096) null else ReleaseRules.parseChecksum(body.string()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

// ================================================================ Favoriten & Flugzeugpräferenzen (Room)

data class FavoriteTrip(val searchId: String, val savedAtMillis: Long, val score: Double, val summary: String, val itinerary: ItineraryDto)

interface FavoritesStore {
    val trips: Flow<List<FavoriteTrip>>
    val aircraftPreferences: Flow<Map<String, AircraftPreferenceEntity>>
    suspend fun toggleTrip(searchId: String, item: ScoredItineraryDto)
    suspend fun removeTrip(itineraryId: String)
    suspend fun setAircraft(typeId: String, status: String, rating: Double?)
}

class RoomFavoritesStore(private val dao: FavoriteDao, private val json: Json) : FavoritesStore {
    override val trips: Flow<List<FavoriteTrip>> = dao.trips().map { list ->
        list.mapNotNull { e ->
            runCatching { json.decodeFromString<ItineraryDto>(e.itineraryJson) }.getOrNull()
                ?.let { FavoriteTrip(e.searchId, e.savedAtMillis, e.score, e.summary, it) }
        }
    }
    override val aircraftPreferences = dao.aircraft().map { list -> list.associateBy { it.aircraftTypeId } }

    override suspend fun toggleTrip(searchId: String, item: ScoredItineraryDto) {
        val id = item.itinerary.id
        val exists = dao.trips().first().any { it.itineraryId == id }
        if (exists) dao.deleteTrip(id)
        else dao.insertTrip(
            FavoriteTripEntity(id, searchId, System.currentTimeMillis(), item.totalScore, item.explanation.summary,
                json.encodeToString(ItineraryDto.serializer(), item.itinerary)),
        )
    }

    override suspend fun removeTrip(itineraryId: String) = dao.deleteTrip(itineraryId)

    override suspend fun setAircraft(typeId: String, status: String, rating: Double?) {
        if (status == "neutral" && rating == null) dao.deleteAircraft(typeId)
        else dao.upsertAircraft(AircraftPreferenceEntity(typeId, status, rating))
    }
}

// ================================================================ Logbuch

interface LogbookStore {
    val entries: Flow<List<FlightLogEntryEntity>>
    suspend fun add(entry: FlightLogEntryEntity)
    suspend fun delete(id: Long)
}

class RoomLogbookStore(private val dao: FlightLogDao) : LogbookStore {
    override val entries = dao.entries()
    override suspend fun add(entry: FlightLogEntryEntity) {
        dao.insert(entry)
    }
    override suspend fun delete(id: Long) = dao.delete(id)
}

// ================================================================ Flugzeugkatalog (Asset aus backend/app/data)

class AircraftCatalog(val source: String, val updated: String, val types: List<AircraftTypeDto>) {
    private val byId = types.associateBy { it.id }
    fun name(id: String?): String = id?.let { byId[it]?.displayName ?: it } ?: "Typ unbekannt"
    fun get(id: String) = byId[id]

    companion object {
        fun load(context: Context, json: Json): AircraftCatalog = try {
            val text = context.assets.open("aircraft_types.json").bufferedReader().use { it.readText() }
            val dto = json.decodeFromString<AircraftCatalogDto>(text)
            AircraftCatalog(dto.source, dto.updated, dto.types.sortedByDescending { it.defaultCoolness })
        } catch (e: Exception) {
            AircraftCatalog("Katalog nicht verfügbar", "-", emptyList())
        }
    }
}

/** Baut die Präferenzen für das Backend aus Einstellungen + Room-Markierungen. */
fun buildPreferences(settings: AppSettings, aircraft: Map<String, AircraftPreferenceEntity>): UserPreferencesDto =
    UserPreferencesDto(
        mode = settings.mode,
        weights = settings.customWeights,
        aircraftPreferences = aircraft.values.map { AircraftPreferenceDto(it.aircraftTypeId, it.status, it.personalRating) },
        preferredAirlines = settings.preferredAirlines,
        flownFactor = settings.flownFactor,
    )

fun combinedPreferences(settings: Flow<AppSettings>, aircraft: Flow<Map<String, AircraftPreferenceEntity>>) =
    combine(settings, aircraft) { s, a -> buildPreferences(s, a) }
