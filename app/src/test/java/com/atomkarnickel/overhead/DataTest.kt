package com.atomkarnickel.overhead

import com.atomkarnickel.overhead.data.AircraftRepository
import com.atomkarnickel.overhead.data.CachedRoute
import com.atomkarnickel.overhead.data.FetchException
import com.atomkarnickel.overhead.data.MemoryRouteCache
import com.atomkarnickel.overhead.data.RouteRepository
import com.atomkarnickel.overhead.data.remote.HexdbService
import com.atomkarnickel.overhead.data.remote.PhotoService
import com.atomkarnickel.overhead.data.remote.ReadsbParser
import com.atomkarnickel.overhead.data.remote.ReadsbSource
import com.atomkarnickel.overhead.data.remote.RouteRecord
import com.atomkarnickel.overhead.domain.LatLon
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ReadsbParserTest {
    @Test
    fun parsesAdsbLolFormat() {
        val r = ReadsbParser.parse(fixture("adsblol_point.json"))
        assertEquals(11, r.aircraft.size)
        assertEquals(1792793569001L, r.nowMillis)
        val baw = r.aircraft.first { it.hex == "406f72" }
        assertEquals("BAW15", baw.callsign)
        assertEquals("G-ZBKF", baw.registration)
        assertEquals("B789", baw.typeCode)
        assertEquals(35000, baw.altitudeFt)
        assertEquals(512.7, baw.groundSpeedKt!!, 1e-6)
        assertEquals(107.48, baw.trackDeg!!, 1e-6)
        assertEquals(-288, baw.verticalRateFpm)
        assertEquals("A5", baw.category)
        assertFalse(baw.onGround)
        assertNull(baw.emergency)
    }

    @Test
    fun parsesAdsbFiFormatWithSecondsAndDescription() {
        val r = ReadsbParser.parse(fixture("adsbfi_point.json"))
        assertEquals(11, r.aircraft.size)
        assertEquals(1792793568000L, r.nowMillis)
        assertEquals("BOEING 787-9 Dreamliner", r.aircraft.first { it.hex == "406f72" }.typeDescription)
    }

    @Test
    fun groundVehiclesAndMissingFields() {
        val r = ReadsbParser.parse(fixture("adsblol_ground.json"))
        val ground = r.aircraft.first { it.callsign == "GUIDO13" }
        assertTrue(ground.onGround)
        assertNull(ground.altitudeFt)
        assertNull(ground.typeCode)
        assertEquals("C2", ground.category)
    }

    @Test
    fun dropsStalePositionsAndEntriesWithoutPosition() {
        val body = """{"ac":[
            {"hex":"aaaaaa","lat":50.0,"lon":8.0,"seen_pos":5,"alt_baro":1000},
            {"hex":"bbbbbb","lat":50.0,"lon":8.0,"seen_pos":120,"alt_baro":1000},
            {"hex":"cccccc","alt_baro":1000},
            {"hex":"~dddddd","lat":50.1,"lon":8.1,"alt_baro":"ground","emergency":"general","squawk":"7700"}
        ],"now":1700000000000}"""
        val r = ReadsbParser.parse(body)
        assertEquals(listOf("aaaaaa", "~dddddd"), r.aircraft.map { it.hex })
        assertEquals("general", r.aircraft[1].emergency)
    }

    @Test
    fun emptyOrMissingListIsEmpty() {
        assertTrue(ReadsbParser.parse("""{"ac":[],"now":1}""").aircraft.isEmpty())
        assertTrue(ReadsbParser.parse("""{"msg":"No error"}""").aircraft.isEmpty())
    }
}

class AircraftRepositoryTest {
    private lateinit var primary: MockWebServer
    private lateinit var fallback: MockWebServer

    @Before
    fun setUp() {
        primary = MockWebServer().apply { start() }
        fallback = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        primary.shutdown()
        fallback.shutdown()
    }

    private fun repo() = AircraftRepository(
        listOf(ReadsbSource.adsbLol(client, primary.url("/").toString()), ReadsbSource.adsbFi(client, fallback.url("/").toString())),
        routes = null, clock = { 5_000L },
    )

    @Test
    fun usesPrimarySortsByDistanceAndSendsOnlyRoundedPosition() = runTest {
        primary.enqueue(json(fixture("adsblol_point.json")))
        val obs = observer(LatLon(50.03456, 8.56789))
        val s = repo().fetch(obs, radiusKm = 100, hideGround = true, withRoutes = false)
        assertEquals("adsb.lol", s.source)
        assertEquals(5_000L, s.fetchedAtMillis)
        assertEquals(11, s.aircraft.size)
        assertEquals(s.aircraft.sortedBy { it.distanceKm }, s.aircraft)
        // Datenschutz: nur 2 Nachkommastellen, Radius 100 km + 2 km Unschärfe = 56 NM
        assertEquals("/v2/point/50.03/8.57/56", primary.takeRequest().path)
        assertEquals(0, fallback.requestCount)
        val nearest = s.aircraft.first()
        assertTrue(nearest.distanceKm < s.aircraft.last().distanceKm)
        assertNotNull(nearest.elevationDeg)
    }

    @Test
    fun filtersByRadiusAndGround() = runTest {
        primary.enqueue(json(fixture("adsblol_point.json")))
        val s = repo().fetch(observer(), radiusKm = 25, hideGround = true, withRoutes = false)
        assertTrue(s.aircraft.all { it.distanceKm <= 25.0 })
        assertTrue(s.aircraft.size < 11)

        primary.enqueue(json(fixture("adsblol_ground.json")))
        val zrh = observer(LatLon(47.46, 8.55))
        assertTrue(repo().fetch(zrh, 25, hideGround = true, withRoutes = false).aircraft.none { it.raw.onGround })
        primary.enqueue(json(fixture("adsblol_ground.json")))
        assertTrue(repo().fetch(zrh, 25, hideGround = false, withRoutes = false).aircraft.any { it.raw.onGround })
    }

    @Test
    fun fallsBackToSecondSourceOnError() = runTest {
        primary.enqueue(MockResponse().setResponseCode(503))
        fallback.enqueue(json(fixture("adsbfi_point.json")))
        val s = repo().fetch(observer(), 100, hideGround = true, withRoutes = false)
        assertEquals("adsb.fi", s.source)
        assertEquals("/api/v2/lat/50.03/lon/8.56/dist/56", fallback.takeRequest().path)
    }

    @Test
    fun throwsWhenAllSourcesFail() = runTest {
        primary.enqueue(MockResponse().setResponseCode(429))
        fallback.enqueue(MockResponse().setResponseCode(500))
        try {
            repo().fetch(observer(), 25, hideGround = true, withRoutes = false)
            fail("FetchException erwartet")
        } catch (e: FetchException) {
            assertTrue(e.message!!.contains("adsb.lol: HTTP 429"))
            assertTrue(e.message!!.contains("adsb.fi: HTTP 500"))
        }
    }

    @Test
    fun relativeToDeduplicatesAndComputesDirection() {
        val o = observer(LatLon(50.0, 8.0))
        val list = AircraftRepository.relativeTo(
            o, listOf(raw("a", 50.1, 8.0), raw("a", 50.1, 8.0), raw("b", 50.0, 8.1), raw("g", 50.0, 8.01, onGround = true, altitudeFt = null)), 50, hideGround = false,
        )
        assertEquals(listOf("g", "b", "a"), list.map { it.hex })
        assertEquals("N", com.atomkarnickel.overhead.domain.Geo.compass(list.first { it.hex == "a" }.bearingDeg))
        assertNull(list.first { it.hex == "g" }.elevationDeg)
    }
}

class RouteRepositoryTest {
    private lateinit var server: MockWebServer
    private val routeHits = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/api/v1/route/icao/") -> {
                        routeHits += path.substringAfterLast('/')
                        when (path.substringAfterLast('/')) {
                            "EZY73HP" -> json(fixture("hexdb_route.json"))
                            "BAW15" -> json("""{"flight":"BAW15","route":"EGLL-WSSS","updatetime":1700000000}""")
                            "SRV500" -> MockResponse().setResponseCode(500)
                            else -> json("""{"status":"404","error":"Route not found."}""", 404)
                        }
                    }
                    path == "/api/v1/airport/icao/LTAI" -> json(fixture("hexdb_airport.json"))
                    path == "/api/v1/airport/icao/EGKK" -> json("""{"iata":"LGW","icao":"EGKK","airport":"London Gatwick","latitude":51.148,"longitude":-0.19}""")
                    path == "/api/v1/airport/icao/EGLL" -> json("""{"iata":"LHR","icao":"EGLL","airport":"London Heathrow","latitude":51.47,"longitude":-0.46}""")
                    path == "/api/v1/airport/icao/WSSS" -> json("""{"iata":"SIN","icao":"WSSS","airport":"Singapore Changi","latitude":1.36,"longitude":103.99}""")
                    else -> json("""{"status":"404"}""", 404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun aircraft(vararg list: Pair<String, LatLon>) =
        AircraftRepository.relativeTo(observer(), list.mapIndexed { i, (cs, p) -> raw("h$i", p.lat, p.lon, callsign = cs) }, 20_000, hideGround = false)

    @Test
    fun resolvesRoutesChecksPlausibilityAndCaches() = runTest {
        var now = 0L
        val cache = MemoryRouteCache()
        val repo = RouteRepository(HexdbService(client, server.url("/").toString()), cache) { now }
        // EZY73HP über Süddeutschland passt zu Antalya → Gatwick; BAW15 über New York passt nicht zu London → Singapur.
        val planes = aircraft("EZY73HP" to LatLon(47.62, 8.39), "BAW15" to LatLon(40.64, -73.78))
        val routes = repo.routesFor(planes)
        val ezy = routes.getValue("EZY73HP")
        assertEquals("AYT → LGW", ezy.label)
        assertEquals("Antalya Airport", ezy.airports.first().name)
        assertEquals(true, ezy.plausible)
        assertEquals(false, routes.getValue("BAW15").plausible)
        assertEquals(listOf("EZY73HP", "BAW15"), routeHits)

        // Zweiter Durchlauf: alles aus dem Cache, keine neuen Abfragen
        val requests = server.requestCount
        repo.routesFor(planes)
        assertEquals(requests, server.requestCount)

        // Nach Ablauf (12 h) wird erneut gefragt
        now += RouteRepository.POSITIVE_TTL_MS + 1
        repo.routesFor(planes)
        assertEquals(listOf("EZY73HP", "BAW15", "EZY73HP", "BAW15"), routeHits)
    }

    @Test
    fun skipsNonAirlineCallsignsGroundAndLimitsLookups() = runTest {
        val repo = RouteRepository(HexdbService(client, server.url("/").toString()), MemoryRouteCache())
        val planes = aircraft("HBJXA" to LatLon(50.0, 8.6), "SHUCK81" to LatLon(50.0, 8.6), "AAA1" to LatLon(50.0, 8.6), "AAA2" to LatLon(50.0, 8.6), "AAA3" to LatLon(50.0, 8.6))
        val routes = repo.routesFor(planes, maxRoutes = 8, maxLookups = 2)
        assertTrue(routes.isEmpty())
        assertEquals(listOf("AAA1", "AAA2"), routeHits)
    }

    @Test
    fun unknownRoutesAreNegativeCachedAndErrorsAreNot() = runTest {
        var now = 0L
        val cache = MemoryRouteCache()
        val repo = RouteRepository(HexdbService(client, server.url("/").toString()), cache) { now }
        val planes = aircraft("XYZ123" to LatLon(50.0, 8.6), "SRV500" to LatLon(50.0, 8.6))
        repo.routesFor(planes)
        repo.routesFor(planes)
        // XYZ123 (404) nur einmal, SRV500 (Serverfehler) erneut
        assertEquals(listOf("XYZ123", "SRV500", "SRV500"), routeHits)
        assertEquals(emptyList<String>(), cache.route("XYZ123")!!.record.airports)
        // Nach Ablauf der 6 h wird auch die unbekannte Route erneut gefragt
        now += RouteRepository.NEGATIVE_TTL_MS + 1
        repo.routesFor(planes)
        assertEquals(listOf("XYZ123", "SRV500", "SRV500", "XYZ123", "SRV500"), routeHits)
    }

    @Test
    fun cachedRecordWithoutAirportCoordinatesIsNotCheckable() = runTest {
        val cache = MemoryRouteCache().apply { putRoute(CachedRoute(RouteRecord("TST1", listOf("XXXX", "YYYY")), 0L)) }
        val repo = RouteRepository(HexdbService(client, server.url("/").toString()), cache) { 1L }
        val r = repo.routesFor(aircraft("TST1" to LatLon(50.0, 8.6))).getValue("TST1")
        assertNull(r.plausible)
        assertEquals("XXXX → YYYY", r.label)
    }
}

class PhotoServiceTest {
    @Test
    fun parsesPhotoWithCreditAndCaches() = runTest {
        val server = MockWebServer().apply { start() }
        server.enqueue(json(fixture("planespotters_photo.json")))
        server.enqueue(json("""{"photos":[]}"""))
        val svc = PhotoService(client, server.url("/").toString())
        val p = svc.photo("408388")!!
        assertEquals("Chris Pitchacaren", p.photographer)
        assertTrue(p.imageUrl.startsWith("https://t.plnspttrs.net/"))
        assertTrue(p.pageUrl.startsWith("https://www.planespotters.net/photo/"))
        assertEquals(p, svc.photo("408388"))
        assertNull(svc.photo("000000"))
        assertEquals(2, server.requestCount)
        assertEquals("/pub/photos/hex/408388", server.takeRequest().path)
        server.shutdown()
    }
}
