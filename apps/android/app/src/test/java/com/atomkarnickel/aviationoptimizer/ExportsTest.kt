package com.atomkarnickel.aviationoptimizer

import com.atomkarnickel.aviationoptimizer.data.AircraftCatalog
import com.atomkarnickel.aviationoptimizer.data.AirportDirectory
import com.atomkarnickel.aviationoptimizer.data.LogbookStore
import com.atomkarnickel.aviationoptimizer.data.itineraryCalendar
import com.atomkarnickel.aviationoptimizer.data.local.AircraftPreferenceEntity
import com.atomkarnickel.aviationoptimizer.data.local.FlightLogEntryEntity
import com.atomkarnickel.aviationoptimizer.data.remote.AircraftTypeDto
import com.atomkarnickel.aviationoptimizer.domain.CalendarExport
import com.atomkarnickel.aviationoptimizer.domain.CalendarFlight
import com.atomkarnickel.aviationoptimizer.domain.LogbookTools
import com.atomkarnickel.aviationoptimizer.domain.LongestFlight
import com.atomkarnickel.aviationoptimizer.domain.TypeCount
import com.atomkarnickel.aviationoptimizer.ui.logbook.LogbookViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Prüft gegen shared/test-vectors/exports.json (Referenz: scripts/gen_export_vectors.py), identisch zur Webversion. */
class ExportsTest {
    private val vectors = testJson.parseToJsonElement(sharedFile("test-vectors/exports.json").readText()).jsonObject
    private val logbook = vectors["logbook"]!!.jsonObject
    private val vectorIds = logbook["entries"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content

    private val entries = logbook["entries"]!!.jsonArray.mapIndexed { i, e ->
        val o = e.jsonObject
        FlightLogEntryEntity(
            id = i + 1L, date = o.s("date"), status = o.s("status"), airline = o.s("airline"), flightNumber = o.s("flight_number"),
            origin = o.s("origin"), destination = o.s("destination"), aircraftTypeId = o.s("aircraft_type_id"), notes = o.s("notes"),
        )
    }
    private val airports = logbook["airports"]!!.jsonObject.mapValues { (_, v) ->
        v.jsonArray[0].jsonPrimitive.double to v.jsonArray[1].jsonPrimitive.double
    }
    private val wishlist = logbook["wishlist"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun statsMatchReference() {
        val e = logbook["expected_stats"]!!.jsonObject
        val s = LogbookTools.stats(entries, wishlist, airports)
        assertEquals(e["flights"]!!.jsonPrimitive.int, s.flights)
        assertEquals(e["planned"]!!.jsonPrimitive.int, s.planned)
        assertEquals(e["types"]!!.jsonPrimitive.int, s.types)
        assertEquals(e["distance_km"]!!.jsonPrimitive.long, s.distanceKm)
        assertEquals(e["unknown_distance"]!!.jsonPrimitive.int, s.unknownDistance)
        val longest = e["longest"]!!.jsonObject
        assertEquals(LongestFlight(vectorIds.indexOf(longest.s("id")) + 1L, longest["km"]!!.jsonPrimitive.long), s.longest)
        assertEquals(e["top_types"]!!.jsonArray.map { TypeCount(it.jsonObject.s("id"), it.jsonObject["count"]!!.jsonPrimitive.int) }, s.topTypes)
        assertEquals(e["wishlist_flown"]!!.jsonArray.map { it.jsonPrimitive.content }, s.wishlistFlown)
        assertEquals(e["wishlist_total"]!!.jsonPrimitive.int, s.wishlistTotal)
    }

    @Test
    fun csvMatchesReferenceAndRoundTrips() {
        val csv = LogbookTools.toCsv(entries)
        assertEquals(logbook.s("expected_csv"), csv)
        val back = LogbookTools.parseLogbookCsv(csv) { true }
        assertEquals(emptyList<String>(), back.errors)
        assertEquals(entries.map { it.copy(id = 0) }, back.entries)
    }

    @Test
    fun csvParserHandlesQuotesBomAndLf() {
        assertEquals(listOf(listOf("a", "b"), listOf("x, \"y\"", "z\nw")), LogbookTools.parseCsv("﻿a,b\n\"x, \"\"y\"\"\",\"z\nw\"\n"))
    }

    @Test
    fun csvImportReportsBadRows() {
        val csv = listOf(
            "date,status,airline,flight_number,origin,destination,aircraft_type_id,notes",
            "2026-01-01,flown,LX,38,ZRH,SFO,A380,",
            "01.01.2026,flown,LX,38,ZRH,SFO,A380,",
            "2026-01-02,maybe,LX,38,ZRH,SFO,A380,",
            "2026-01-03,flown,LX,38,ZURICH,SFO,A380,",
            "2026-01-04,flown,LX,38,ZRH,SFO,XYZ,",
        ).joinToString("\n")
        val r = LogbookTools.parseLogbookCsv(csv) { it == "A380" }
        assertEquals(1, r.entries.size)
        assertEquals(4, r.errors.size)
        assertTrue(r.errors[0].startsWith("Zeile 3"))
        assertTrue(LogbookTools.parseLogbookCsv("a,b\n1,2") { true }.errors[0].contains("Kopfzeile"))
    }

    @Test
    fun duplicatesAreNotImportedTwice() {
        assertEquals(0, LogbookTools.newEntries(entries, entries.map { it.copy(id = 0) }).size)
    }

    @Test
    fun icsMatchesReferenceByteForByte() {
        val ics = vectors["ics"]!!.jsonObject
        val flights = ics["flights"]!!.jsonArray.map {
            val o = it.jsonObject
            CalendarFlight(
                uid = o.s("uid"), carrier = o.s("carrier"), flightNumber = o.s("flight_number"), origin = o.s("origin"),
                destination = o.s("destination"), departureUtc = o["departure_utc"]!!.jsonPrimitive.contentOrNull,
                arrivalUtc = o["arrival_utc"]!!.jsonPrimitive.contentOrNull, departureLocal = o.s("departure_local"),
                arrivalLocal = o.s("arrival_local"), aircraft = o["aircraft"]!!.jsonPrimitive.contentOrNull, aircraftStatus = o.s("aircraft_status"),
            )
        }
        assertEquals(ics.s("expected"), CalendarExport.build(ics.s("calendar_name"), flights, Instant.parse(ics.s("now"))))
    }

    @Test
    fun foldNeverExceeds75OctetsOrSplitsCharacters() {
        val line = "SUMMARY:" + "✈ä".repeat(40)
        val parts = CalendarExport.fold(line)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
        assertEquals(line, parts.mapIndexed { i, p -> if (i == 0) p else p.drop(1) }.joinToString(""))
    }

    @Test
    fun itineraryBecomesOneEventPerSegment() {
        val item = sampleItem("x", 1, 500.0, stops = 1)
        val (name, flights) = itineraryCalendar(item.itinerary, emptyCatalog)
        assertEquals(item.itinerary.legs.sumOf { it.segments.size }, flights.size)
        assertTrue(name.contains("nicht gebucht"))
        assertEquals(flights.size, Regex("BEGIN:VEVENT").findAll(CalendarExport.build(name, flights)).count())
    }
}

class FakeLogbook : LogbookStore {
    val state = MutableStateFlow<List<FlightLogEntryEntity>>(emptyList())
    private var nextId = 1L
    override val entries = state
    override suspend fun add(entry: FlightLogEntryEntity) {
        state.value = (state.value + entry.copy(id = nextId++)).sortedByDescending { it.date }
    }
    override suspend fun delete(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LogbookViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val a380 = AircraftTypeDto("A380", "Airbus", "A380-800", "A380", "500", "Langstrecke", "active", 10.0)
    private val catalog = AircraftCatalog("test", "2026-10-19", listOf(a380))
    private val airports = AirportDirectory(mapOf("ZRH" to (47.46 to 8.55), "SFO" to (37.62 to -122.38)))

    @Test
    fun importAddsEntriesMarksFlownAndUpdatesStats() = runTest {
        val store = FakeLogbook()
        val favorites = FakeFavorites().apply { aircraftState.value = mapOf("A380" to AircraftPreferenceEntity("A380", "wishlist", null)) }
        val vm = LogbookViewModel(store, favorites, catalog, airports)
        backgroundScope.launch { vm.stats.collect {} }
        vm.importCsv(
            "date,status,airline,flight_number,origin,destination,aircraft_type_id,notes\r\n" +
                "2026-09-01,flown,LX,38,ZRH,SFO,A380,\r\n2026-09-01,flown,LX,38,ZRH,SFO,A380,\r\n2026-09-02,flown,LX,39,ZRH,SFO,B747,\r\n",
        )
        advanceUntilIdle()
        assertEquals(1, store.state.value.size)
        assertTrue(vm.importMessage.value!!.startsWith("1 Einträge importiert, 1 bereits vorhanden, 1 Zeilen übersprungen"))
        // Wunschliste bleibt Wunschliste (nur neutrale Typen werden als geflogen markiert)
        assertEquals("wishlist", favorites.aircraftState.value["A380"]!!.status)
        val stats = vm.stats.value
        assertEquals(1, stats.flights)
        assertEquals(listOf("A380"), stats.wishlistFlown)
        assertEquals(9376L, stats.distanceKm)
    }

    @Test
    fun unreadableOrOversizedFilesAreRejected() = runTest {
        val vm = LogbookViewModel(FakeLogbook(), FakeFavorites(), catalog, airports)
        vm.importCsv(null)
        assertEquals("Datei konnte nicht gelesen werden.", vm.importMessage.value)
        vm.importCsv("x".repeat(1_000_001))
        assertEquals("Datei zu groß (max. 1 MB).", vm.importMessage.value)
    }
}
