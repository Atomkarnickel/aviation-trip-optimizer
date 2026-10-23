package com.atomkarnickel.overhead

import com.atomkarnickel.overhead.data.DeviceLocation
import com.atomkarnickel.overhead.data.SavedLocation
import com.atomkarnickel.overhead.data.Settings
import com.atomkarnickel.overhead.data.SettingsRepository
import com.atomkarnickel.overhead.domain.Aircraft
import com.atomkarnickel.overhead.domain.LatLon
import com.atomkarnickel.overhead.domain.LocationSource
import com.atomkarnickel.overhead.domain.Observer
import com.atomkarnickel.overhead.domain.RawAircraft
import com.atomkarnickel.overhead.domain.ReferenceData
import com.atomkarnickel.overhead.domain.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import java.io.File

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.classLoader!!.getResource("fixtures/$name")) { "Fixture $name fehlt" }.readText()

/** Referenzdaten aus den echten App-Assets (Arbeitsverzeichnis der Unit-Tests = app/). */
val reference: ReferenceData by lazy {
    ReferenceData.parse(File("src/main/assets/aircraft_types.json").readText(), File("src/main/assets/airlines.json").readText())
}

val client: OkHttpClient = OkHttpClient()

fun json(body: String, code: Int = 200): MockResponse = MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

val FRANKFURT = LatLon(50.03, 8.56)

fun observer(at: LatLon = FRANKFURT, source: LocationSource = LocationSource.DEVICE) = Observer(at, source, 0L)

fun raw(
    hex: String,
    lat: Double,
    lon: Double,
    altitudeFt: Int? = 30000,
    callsign: String? = null,
    onGround: Boolean = false,
    category: String? = "A3",
    typeCode: String? = "A320",
    squawk: String? = null,
) = RawAircraft(hex = hex, callsign = callsign, typeCode = typeCode, category = category, lat = lat, lon = lon, altitudeFt = altitudeFt, onGround = onGround, squawk = squawk, trackDeg = 90.0)

fun snapshot(
    aircraft: List<Aircraft> = emptyList(),
    fetchedAt: Long = 1_000_000L,
    error: String? = null,
    radiusKm: Int = 25,
) = Snapshot(observer(), fetchedAt, "test", radiusKm, aircraft, error = error)

class FakeSettings(initial: Settings = Settings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings = state
    override suspend fun update(transform: (Settings) -> Settings) {
        state.value = transform(state.value)
    }
}

class FakeDeviceLocation(
    var permission: Boolean = true,
    var background: Boolean = false,
    var location: SavedLocation? = SavedLocation(FRANKFURT, 42L),
) : DeviceLocation {
    var calls = 0
    override fun hasPermission() = permission
    override fun hasBackgroundPermission() = permission && background
    override suspend fun current(): SavedLocation? {
        calls++
        return if (permission) location else null
    }
}
