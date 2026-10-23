package com.atomkarnickel.overhead

import com.atomkarnickel.overhead.data.AircraftRepository
import com.atomkarnickel.overhead.data.LocationMode
import com.atomkarnickel.overhead.data.LocationResolver
import com.atomkarnickel.overhead.data.MemorySnapshotStore
import com.atomkarnickel.overhead.data.RefreshOutcome
import com.atomkarnickel.overhead.data.Refresher
import com.atomkarnickel.overhead.data.SavedLocation
import com.atomkarnickel.overhead.data.Settings
import com.atomkarnickel.overhead.data.remote.AdsbSource
import com.atomkarnickel.overhead.data.remote.AircraftPhoto
import com.atomkarnickel.overhead.data.remote.ReadsbParser
import com.atomkarnickel.overhead.domain.AltitudeUnit
import com.atomkarnickel.overhead.domain.LatLon
import com.atomkarnickel.overhead.domain.LocationSource
import com.atomkarnickel.overhead.domain.Route
import com.atomkarnickel.overhead.domain.RouteAirport
import com.atomkarnickel.overhead.domain.WidgetModelBuilder
import com.atomkarnickel.overhead.ui.HomeViewModel
import com.atomkarnickel.overhead.ui.PhotoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

/** ADS-B-Quelle aus dem Speicher: gibt die Fixture zurück oder wirft. */
class FakeSource(var fail: Boolean = false, private val body: String = fixture("adsblol_point.json")) : AdsbSource {
    override val name = "fake"
    override val attribution = "Fake"
    var calls = 0
    override suspend fun nearby(center: LatLon, radiusKm: Int): ReadsbParser.Result {
        calls++
        if (fail) throw java.io.IOException("fake: offline")
        return ReadsbParser.parse(body)
    }
}

class LocationResolverTest {
    @Test
    fun fixedLocationWins() = runTest {
        val device = FakeDeviceLocation()
        val s = Settings(locationMode = LocationMode.FIXED, fixedLocation = LatLon(47.0, 8.0))
        val o = LocationResolver(device, FakeSettings(s)).resolve(s, foreground = true)!!
        assertEquals(LocationSource.FIXED, o.source)
        assertEquals(0, device.calls)
    }

    @Test
    fun deviceLocationIsUsedAndRemembered() = runTest {
        val settings = FakeSettings()
        val o = LocationResolver(FakeDeviceLocation(), settings).resolve(settings.state.value, foreground = true)!!
        assertEquals(LocationSource.DEVICE, o.source)
        assertEquals(FRANKFURT, settings.state.value.lastDeviceLocation!!.position)
    }

    @Test
    fun backgroundWithoutAlwaysPermissionFallsBackToSavedLocation() = runTest {
        val device = FakeDeviceLocation(background = false)
        val s = Settings(lastDeviceLocation = SavedLocation(LatLon(48.0, 9.0), 7L))
        val o = LocationResolver(device, FakeSettings(s)).resolve(s, foreground = false)!!
        assertEquals(LocationSource.SAVED, o.source)
        assertEquals(7L, o.locationTimeMillis)
        assertEquals(0, device.calls)
    }

    @Test
    fun noPermissionAndNothingSavedMeansNoLocation() = runTest {
        val s = Settings()
        assertNull(LocationResolver(FakeDeviceLocation(permission = false), FakeSettings(s)).resolve(s, foreground = true))
    }
}

class RefresherTest {
    private fun refresher(source: FakeSource, store: MemorySnapshotStore, settings: FakeSettings = FakeSettings(), device: FakeDeviceLocation = FakeDeviceLocation()) =
        Refresher(settings, LocationResolver(device, settings), AircraftRepository(listOf(source), null) { 99L }, store, dispatcher = Dispatchers.Unconfined)

    @Test
    fun successSavesSnapshot() = runTest {
        val store = MemorySnapshotStore()
        val out = refresher(FakeSource(), store, FakeSettings(Settings(radiusKm = 100))).refresh(foreground = true, withRoutes = false)
        assertTrue(out is RefreshOutcome.Success)
        assertEquals(11, store.latest.value!!.aircraft.size)
        assertNull(store.latest.value!!.error)
    }

    @Test
    fun failureKeepsPreviousSnapshotAndMarksIt() = runTest {
        val store = MemorySnapshotStore(snapshot(fetchedAt = 5L))
        val out = refresher(FakeSource(fail = true), store).refresh(foreground = true)
        assertTrue(out is RefreshOutcome.Failed)
        assertEquals(5L, store.latest.value!!.fetchedAtMillis)
        assertTrue(store.latest.value!!.error!!.contains("fake: offline"))
    }

    @Test
    fun noLocationDoesNotCallSource() = runTest {
        val source = FakeSource()
        val out = refresher(source, MemorySnapshotStore(), device = FakeDeviceLocation(permission = false)).refresh(foreground = true)
        assertEquals(RefreshOutcome.NoLocation, out)
        assertEquals(0, source.calls)
    }
}

class WidgetModelTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun emptyStates() {
        val none = WidgetModelBuilder.build(null, reference, AltitudeUnit.METERS, 0L, zone = zone)
        assertTrue(none.message!!.contains("Standort"))
        val quiet = WidgetModelBuilder.build(snapshot(), reference, AltitudeUnit.METERS, 1_000_000L, zone = zone)
        assertEquals("Niemand über dir", quiet.headline)
        assertTrue(quiet.message!!.contains("25 km"))
    }

    @Test
    fun rowsNearestFirstWithRouteTypeAndDirection() {
        val planes = AircraftRepository.relativeTo(
            observer(LatLon(50.0, 8.0)),
            listOf(
                raw("near", 50.05, 8.0, altitudeFt = 3000, callsign = "SWR38", typeCode = "A21N"),
                raw("far", 50.0, 8.2, altitudeFt = 36000, callsign = "DLH4AB", typeCode = "B748"),
                raw("ground", 50.0, 8.001, onGround = true, altitudeFt = null),
            ),
            50, hideGround = false,
        )
        val route = Route("SWR38", listOf(RouteAirport("LSZH", "ZRH"), RouteAirport("EDDF", "FRA")), plausible = true)
        val snap = snapshot(planes).copy(routes = mapOf("SWR38" to route))
        val m = WidgetModelBuilder.build(snap, reference, AltitudeUnit.METERS, 1_000_000L, zone = zone)
        assertEquals("2 Flugzeuge über dir", m.headline)
        assertEquals("00:16", m.status)
        assertEquals(listOf("near", "far"), m.rows.map { it.hex })
        val first = m.rows[0]
        assertEquals("Airbus A321neo · SWR38", first.title)
        assertEquals("ZRH → FRA", first.subtitle)
        assertEquals("↑ N", first.direction)
        assertEquals("5,6 km", first.distance)
        assertEquals("910 m", first.altitude.substringBefore(" ↗").substringBefore(" →").trim())
        assertEquals("Lufthansa", m.rows[1].subtitle)
    }

    @Test
    fun implausibleRouteIsHiddenAndAirlineShownInstead() {
        val planes = AircraftRepository.relativeTo(observer(), listOf(raw("x", 50.05, 8.56, callsign = "SWR38")), 50, hideGround = false)
        val wrong = Route("SWR38", listOf(RouteAirport("LSZH", "ZRH"), RouteAirport("KSFO", "SFO")), plausible = false)
        val m = WidgetModelBuilder.build(snapshot(planes).copy(routes = mapOf("SWR38" to wrong)), reference, AltitudeUnit.FEET, 1_000_000L, zone = zone)
        assertEquals("SWISS", m.rows.single().subtitle)
        assertTrue(m.rows.single().altitude.startsWith("30 000 ft"))
    }

    @Test
    fun staleOfflineAndEmergency() {
        val planes = AircraftRepository.relativeTo(observer(), listOf(raw("e", 50.05, 8.56, squawk = "7700")), 50, hideGround = false)
        val old = WidgetModelBuilder.build(snapshot(planes), reference, AltitudeUnit.METERS, 1_000_000L + WidgetModelBuilder.STALE_AFTER_MS + 1, zone = zone)
        assertTrue(old.stale)
        assertTrue(old.status.endsWith("veraltet"))
        assertTrue(old.rows.single().emergency)
        val offline = WidgetModelBuilder.build(snapshot(planes, error = "x"), reference, AltitudeUnit.METERS, 1_000_000L, zone = zone)
        assertTrue(offline.status.endsWith("offline"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        source: FakeSource = FakeSource(),
        device: FakeDeviceLocation = FakeDeviceLocation(),
        settings: FakeSettings = FakeSettings(Settings(radiusKm = 100)),
        store: MemorySnapshotStore = MemorySnapshotStore(),
        photo: suspend (String) -> AircraftPhoto? = { null },
        image: suspend (String) -> ByteArray? = { null },
    ) = HomeViewModel(
        settings,
        Refresher(settings, LocationResolver(device, settings), AircraftRepository(listOf(source), null), store, dispatcher = Dispatchers.Unconfined),
        store, device, reference, photo, image,
    )

    @Test
    fun refreshLoadsAircraft() = runTest(main) {
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(11, s.snapshot!!.aircraft.size)
        assertEquals(false, s.refreshing)
        assertEquals(false, s.needsLocation)
    }

    @Test
    fun missingLocationIsReported() = runTest(main) {
        val vm = vm(device = FakeDeviceLocation(permission = false))
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.needsLocation)
    }

    @Test
    fun errorIsReportedAndPreviousDataKept() = runTest(main) {
        val store = MemorySnapshotStore(snapshot(fetchedAt = 3L))
        val vm = vm(source = FakeSource(fail = true), store = store)
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.lastError!!.contains("offline"))
        assertEquals(3L, vm.state.value.snapshot!!.fetchedAtMillis)
    }

    @Test
    fun fixedLocationFromMapIsStoredAndUsed() = runTest(main) {
        val settings = FakeSettings(Settings(radiusKm = 100))
        val vm = vm(settings = settings, device = FakeDeviceLocation(permission = false))
        vm.setFixedLocation(LatLon(50.1, 8.6))
        advanceUntilIdle()
        assertEquals(LocationMode.FIXED, settings.state.value.locationMode)
        assertEquals(LocationSource.FIXED, vm.state.value.snapshot!!.observer.source)
        assertEquals(false, vm.state.value.needsLocation)
    }

    @Test
    fun selectingLoadsPhoto() = runTest(main) {
        val photo = AircraftPhoto("https://t.plnspttrs.net/x.jpg", "https://www.planespotters.net/photo/1", "Jane")
        val vm = vm(photo = { if (it == "406f72") photo else null }, image = { byteArrayOf(1, 2, 3) })
        vm.refresh()
        advanceUntilIdle()
        vm.select("406f72")
        advanceUntilIdle()
        val loaded = vm.state.value.photo as PhotoState.Loaded
        assertEquals("Jane", loaded.photo.photographer)
        assertEquals("BAW15", vm.state.value.selected!!.raw.callsign)
        vm.select("4070e7")
        advanceUntilIdle()
        assertEquals(PhotoState.None, vm.state.value.photo)
        vm.select(null)
        advanceUntilIdle()
        assertNull(vm.state.value.selected)
        assertEquals(PhotoState.Idle, vm.state.value.photo)
    }
}

class SettingsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun defaultsAndForwardCompatibility() {
        val s = json.decodeFromString<Settings>("""{"radiusKm":50,"futureField":true}""")
        assertEquals(50, s.radiusKm)
        assertEquals(LocationMode.DEVICE, s.locationMode)
        assertEquals(15, s.widgetIntervalMinutes)
        val back = json.decodeFromString<Settings>(json.encodeToString(Settings.serializer(), s.copy(fixedLocation = LatLon(1.0, 2.0))))
        assertEquals(LatLon(1.0, 2.0), back.fixedLocation)
    }
}
