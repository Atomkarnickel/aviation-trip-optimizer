package com.atomkarnickel.aviationoptimizer.domain

import com.atomkarnickel.aviationoptimizer.data.local.FlightLogEntryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Logbuch-Statistik, Logbuch-CSV und Kalender-Export (.ics).
 * Referenz: scripts/gen_export_vectors.py – Android und Web werden gegen shared/test-vectors/exports.json getestet.
 */

fun distanceKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val lat1 = Math.toRadians(a.first); val lon1 = Math.toRadians(a.second)
    val lat2 = Math.toRadians(b.first); val lon2 = Math.toRadians(b.second)
    val s1 = sin((lat2 - lat1) / 2); val s2 = sin((lon2 - lon1) / 2)
    val h = s1 * s1 + cos(lat1) * cos(lat2) * s2 * s2
    return 2 * 6371.0 * asin(sqrt(h))
}

data class TypeCount(val id: String, val count: Int)
data class LongestFlight(val entryId: Long, val km: Long)

data class LogbookStats(
    val flights: Int,
    val planned: Int,
    val types: Int,
    val distanceKm: Long,
    val unknownDistance: Int,
    val longest: LongestFlight?,
    val topTypes: List<TypeCount>,
    val wishlistFlown: List<String>,
    val wishlistTotal: Int,
)

object LogbookTools {
    val CSV_COLUMNS = listOf("date", "status", "airline", "flight_number", "origin", "destination", "aircraft_type_id", "notes")

    /** Math.rint rundet wie Python round() bei exakt .5 auf die gerade Zahl. */
    private fun round(x: Double): Long = Math.rint(x).toLong()

    fun stats(entries: List<FlightLogEntryEntity>, wishlist: List<String>, airports: Map<String, Pair<Double, Double>>): LogbookStats {
        val flown = entries.filter { it.status == "flown" }
        var total = 0.0
        var unknown = 0
        var longest: Pair<Long, Double>? = null
        val counts = LinkedHashMap<String, Int>()
        for (e in flown) {
            counts[e.aircraftTypeId] = (counts[e.aircraftTypeId] ?: 0) + 1
            val a = airports[e.origin]
            val b = airports[e.destination]
            if (a == null || b == null) { unknown++; continue }
            val d = distanceKm(a, b)
            total += d
            if (longest == null || d > longest.second) longest = e.id to d
        }
        val top = counts.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(3).map { TypeCount(it.key, it.value) }
        return LogbookStats(
            flights = flown.size,
            planned = entries.size - flown.size,
            types = counts.size,
            distanceKm = round(total),
            unknownDistance = unknown,
            longest = longest?.let { LongestFlight(it.first, round(it.second)) },
            topTypes = top,
            wishlistFlown = wishlist.filter { it in counts }.sorted(),
            wishlistTotal = wishlist.size,
        )
    }

    // ---------- CSV ----------

    private fun csvField(v: String) = if (v.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun toCsv(entries: List<FlightLogEntryEntity>): String = buildString {
        append(CSV_COLUMNS.joinToString(",")).append("\r\n")
        for (e in entries) {
            append(listOf(e.date, e.status, e.airline, e.flightNumber, e.origin, e.destination, e.aircraftTypeId, e.notes).joinToString(",") { csvField(it) })
            append("\r\n")
        }
    }

    /** RFC-4180-Parser (Anführungszeichen, eingebettete Kommas und Zeilenumbrüche, CRLF oder LF, BOM). */
    fun parseCsv(text: String): List<List<String>> {
        val src = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < src.length && src[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    c == '\r' && i + 1 < src.length && src[i + 1] == '\n' -> Unit
                    else -> field.append(c)
                }
            } else when (c) {
                '"' -> if (field.isEmpty()) quoted = true else field.append(c)
                ',' -> { row.add(field.toString()); field.clear() }
                '\r', '\n' -> {
                    if (c == '\r' && i + 1 < src.length && src[i + 1] == '\n') i++
                    row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows.filterNot { it.size == 1 && it[0].isEmpty() }
    }

    data class CsvImport(val entries: List<FlightLogEntryEntity>, val errors: List<String>)

    fun parseLogbookCsv(text: String, knownAircraft: (String) -> Boolean): CsvImport {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return CsvImport(emptyList(), listOf("Datei ist leer."))
        val header = rows[0].map { it.trim().lowercase() }
        val index = CSV_COLUMNS.map { header.indexOf(it) }
        if (index.any { it < 0 }) return CsvImport(emptyList(), listOf("Kopfzeile muss diese Spalten enthalten: ${CSV_COLUMNS.joinToString(", ")}"))
        val iata = Regex("^[A-Za-z]{3}$")
        val entries = mutableListOf<FlightLogEntryEntity>()
        val errors = mutableListOf<String>()
        rows.drop(1).forEachIndexed { n, r ->
            val line = n + 2
            val (date, status, airline, flight, origin) = index.take(5).map { r.getOrElse(it) { "" }.trim() }
            val (destination, type, notes) = index.drop(5).map { r.getOrElse(it) { "" }.trim() }
            val error = when {
                !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date) || runCatching { LocalDate.parse(date) }.isFailure -> "ungültiges Datum „$date“."
                status != "flown" && status != "planned" -> "Status muss flown oder planned sein."
                listOf(origin, destination).any { it.isNotEmpty() && !iata.matches(it) } ->
                    "„${listOf(origin, destination).first { it.isNotEmpty() && !iata.matches(it) }}“ ist kein IATA-Flughafencode."
                !knownAircraft(type) -> "Flugzeugtyp „$type“ ist nicht im Katalog."
                else -> null
            }
            if (error != null) errors.add("Zeile $line: $error")
            else entries.add(
                FlightLogEntryEntity(
                    date = date, status = status, airline = airline.uppercase().take(2), flightNumber = flight.uppercase().take(8),
                    origin = origin.uppercase(), destination = destination.uppercase(), aircraftTypeId = type, notes = notes.take(200),
                ),
            )
        }
        return CsvImport(entries, errors)
    }

    /** Liefert nur Einträge, die (Datum, Status, Flug, Strecke, Typ) noch nicht im Logbuch stehen. */
    fun newEntries(existing: List<FlightLogEntryEntity>, imported: List<FlightLogEntryEntity>): List<FlightLogEntryEntity> {
        fun key(e: FlightLogEntryEntity) = listOf(e.date, e.status, e.airline, e.flightNumber, e.origin, e.destination, e.aircraftTypeId).joinToString("|")
        val seen = existing.map(::key).toMutableSet()
        return imported.filter { seen.add(key(it)) }
    }
}

// ---------- Kalender (.ics) ----------

data class CalendarFlight(
    val uid: String,
    val carrier: String,
    val flightNumber: String,
    val origin: String,
    val destination: String,
    val departureUtc: String?,
    val arrivalUtc: String?,
    val departureLocal: String,
    val arrivalLocal: String,
    val aircraft: String?,
    val aircraftStatus: String,
)

object CalendarExport {
    private val STATUS_TEXT = mapOf(
        "confirmed" to "bestätigt",
        "scheduled" to "geplant, nicht garantiert",
        "historical" to "historisch beobachtet, nicht garantiert",
    )
    private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private fun text(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    private fun utc(v: String): String {
        val instant = runCatching { OffsetDateTime.parse(v).toInstant() }.getOrElse { LocalDateTime.parse(v).toInstant(ZoneOffset.UTC) }
        return UTC_FORMAT.format(instant)
    }

    private fun local(v: String) = LOCAL_FORMAT.format(LocalDateTime.parse(v))

    /** RFC 5545: höchstens 75 Oktette pro Zeile, Folgezeilen beginnen mit Leerzeichen, Zeichen werden nie zerteilt. */
    fun fold(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var size = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val n = ch.toByteArray(Charsets.UTF_8).size
            if (size + n > 75) {
                out.add(current.toString()); current.clear(); current.append(' '); size = 1
            }
            current.append(ch); size += n
            i += Character.charCount(cp)
        }
        out.add(current.toString())
        return out
    }

    fun build(calendarName: String, flights: List<CalendarFlight>, now: Instant = Instant.now()): String {
        val stamp = UTC_FORMAT.format(now)
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Aviation Trip Optimizer//DE", "CALSCALE:GREGORIAN", "METHOD:PUBLISH", "X-WR-CALNAME:" + text(calendarName))
        for (f in flights) {
            val number = if (f.flightNumber.isNotEmpty()) " ${f.flightNumber}" else ""
            val aircraft = if (f.aircraft != null) "Flugzeug: ${f.aircraft} (${STATUS_TEXT[f.aircraftStatus] ?: "Status unbekannt"})" else "Flugzeug: unbekannt"
            val description = "$aircraft\nNicht gebucht – Vorschlag aus Aviation Trip Optimizer. Vor der Buchung Zeiten und Flugzeugtyp bei der Airline prüfen."
            val hasUtc = f.departureUtc != null && f.arrivalUtc != null
            lines += listOf(
                "BEGIN:VEVENT",
                "UID:${f.uid}@aviation-trip-optimizer",
                "DTSTAMP:$stamp",
                "DTSTART:" + if (hasUtc) utc(f.departureUtc!!) else local(f.departureLocal),
                "DTEND:" + if (hasUtc) utc(f.arrivalUtc!!) else local(f.arrivalLocal),
                "SUMMARY:" + text("✈ ${f.carrier}$number ${f.origin} → ${f.destination}"),
                "LOCATION:" + text(f.origin),
                "DESCRIPTION:" + text(description),
                "END:VEVENT",
            )
        }
        lines += "END:VCALENDAR"
        return lines.flatMap(::fold).joinToString("") { "$it\r\n" }
    }
}
