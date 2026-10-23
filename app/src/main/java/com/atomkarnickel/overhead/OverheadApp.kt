package com.atomkarnickel.overhead

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.atomkarnickel.overhead.data.AircraftRepository
import com.atomkarnickel.overhead.data.AndroidDeviceLocation
import com.atomkarnickel.overhead.data.DataStoreSettings
import com.atomkarnickel.overhead.data.DeviceLocation
import com.atomkarnickel.overhead.data.FileRouteCache
import com.atomkarnickel.overhead.data.FileSnapshotStore
import com.atomkarnickel.overhead.data.LocationResolver
import com.atomkarnickel.overhead.data.Refresher
import com.atomkarnickel.overhead.data.RouteRepository
import com.atomkarnickel.overhead.data.SettingsRepository
import com.atomkarnickel.overhead.data.SnapshotStore
import com.atomkarnickel.overhead.data.remote.HexdbService
import com.atomkarnickel.overhead.data.remote.PhotoService
import com.atomkarnickel.overhead.data.remote.ReadsbSource
import com.atomkarnickel.overhead.data.remote.ReleaseRepository
import com.atomkarnickel.overhead.data.remote.buildHttpClient
import com.atomkarnickel.overhead.domain.ReferenceData
import com.atomkarnickel.overhead.widget.OverheadWidget
import com.atomkarnickel.overhead.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "overhead")

/** Einfache manuelle Abhängigkeitsverwaltung – auch von Widget und Worker aus erreichbar. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = buildHttpClient("Overhead/${BuildConfig.VERSION_NAME} (+${BuildConfig.CONTACT_URL})")

    val reference: ReferenceData by lazy {
        runCatching {
            ReferenceData.parse(
                context.assets.open("aircraft_types.json").bufferedReader().use { it.readText() },
                context.assets.open("airlines.json").bufferedReader().use { it.readText() },
            )
        }.getOrDefault(ReferenceData.EMPTY)
    }

    val settings: SettingsRepository = DataStoreSettings(context.dataStore)
    val snapshots: SnapshotStore = FileSnapshotStore(File(context.filesDir, "snapshot.json"))
    val deviceLocation: DeviceLocation = AndroidDeviceLocation(context)
    private val sources = listOf(ReadsbSource.adsbLol(http), ReadsbSource.adsbFi(http))
    val aircraft: AircraftRepository by lazy {
        AircraftRepository(sources, RouteRepository(HexdbService(http), FileRouteCache(File(context.filesDir, "routes.json"))))
    }
    val attributions: List<String> get() = sources.map { it.attribution }
    val photos = PhotoService(http)
    val releases = ReleaseRepository(http, BuildConfig.GITHUB_REPOSITORY, BuildConfig.APK_PREFIX)

    @Volatile private var lastWidgetUpdate = 0L

    val refresher: Refresher by lazy {
        Refresher(settings, LocationResolver(deviceLocation, settings), aircraft, snapshots, onSaved = {
            // Live-Ansicht aktualisiert alle paar Sekunden – das Widget höchstens einmal pro Minute nachziehen.
            val now = System.currentTimeMillis()
            if (now - lastWidgetUpdate > 60_000L) {
                lastWidgetUpdate = now
                OverheadWidget.updateAll(context)
            }
        })
    }

    /** Widget sofort neu zeichnen (z. B. nach Hintergrundabruf oder geänderten Einstellungen). */
    suspend fun updateWidgetsNow() {
        lastWidgetUpdate = System.currentTimeMillis()
        OverheadWidget.updateAll(context)
    }
}

class OverheadApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MapLibre.getInstance(this)
        container.appScope.launch { RefreshScheduler.syncWithWidgets(this@OverheadApp) }
    }
}

val Context.container: AppContainer get() = (applicationContext as OverheadApp).container
