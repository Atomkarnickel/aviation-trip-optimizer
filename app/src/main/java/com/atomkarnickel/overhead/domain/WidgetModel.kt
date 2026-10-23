package com.atomkarnickel.overhead.domain

import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Eine Zeile im Widget – alles schon fertig formatiert. */
data class WidgetRow(
    val hex: String,
    val title: String,
    val subtitle: String,
    val distance: String,
    val altitude: String,
    val direction: String,
    val emergency: Boolean,
)

data class WidgetModel(
    val headline: String,
    val status: String,
    val rows: List<WidgetRow>,
    /** Hinweis statt Liste (kein Standort, noch keine Daten, niemand in der Nähe). */
    val message: String?,
    val stale: Boolean,
)

/** Baut den Widget-Inhalt aus dem letzten Abruf. Reine Funktion → testbar ohne Android. */
object WidgetModelBuilder {
    const val STALE_AFTER_MS = 30 * 60_000L

    fun build(
        snapshot: Snapshot?,
        reference: ReferenceData,
        unit: AltitudeUnit,
        now: Long,
        maxRows: Int = 4,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WidgetModel {
        if (snapshot == null) {
            return WidgetModel("Über dir", "Noch keine Daten", emptyList(), "Tippen, um den Standort festzulegen und Flugzeuge zu laden.", false)
        }
        val airborne = snapshot.aircraft.filter { !it.raw.onGround }
        val stale = now - snapshot.fetchedAtMillis > STALE_AFTER_MS
        val status = buildString {
            append(Format.time(snapshot.fetchedAtMillis, zone))
            if (snapshot.error != null) append(" · offline") else if (stale) append(" · veraltet")
        }
        val headline = when (airborne.size) {
            0 -> "Niemand über dir"
            1 -> "1 Flugzeug über dir"
            else -> "${airborne.size} Flugzeuge über dir"
        }
        val rows = airborne.take(maxRows).map { row(it, snapshot, reference, unit) }
        val message = if (rows.isEmpty()) "Im Umkreis von ${snapshot.radiusKm} km ist gerade kein Flugzeug in der Luft." else null
        return WidgetModel(headline, status, rows, message, stale || snapshot.error != null)
    }

    fun row(a: Aircraft, snapshot: Snapshot, reference: ReferenceData, unit: AltitudeUnit): WidgetRow {
        val r = a.raw
        val type = reference.typeName(r.typeCode, r.typeDescription) ?: AircraftKind.of(r.category).label
        val route = snapshot.routeFor(a)?.takeIf { it.plausible != false }?.label
        val who = r.callsign ?: r.registration ?: r.hex.uppercase()
        val subtitle = listOfNotNull(route ?: reference.airlineName(r.callsign), r.registration?.takeIf { it != who })
            .joinToString(" · ").ifEmpty { AircraftKind.of(r.category).label }
        return WidgetRow(
            hex = r.hex,
            title = "$type · $who",
            subtitle = subtitle,
            distance = Format.distance(a.distanceKm),
            altitude = Format.altitude(r.altitudeFt, r.onGround, unit) + Format.climbArrow(r.verticalRateFpm).let { if (it.isEmpty()) "" else " $it" },
            direction = "${Geo.arrow(a.bearingDeg)} ${Geo.compass(a.bearingDeg)}",
            emergency = r.emergency != null || r.squawk in EMERGENCY_SQUAWKS,
        )
    }

    val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")
}

/** Projektion für das Mini-Radar: Norden oben, Beobachter in der Mitte, Rand = Suchradius. */
object RadarProjection {
    data class Point(val x: Float, val y: Float)

    fun project(bearingDeg: Double, distanceKm: Double, radiusKm: Double, sizePx: Float, paddingPx: Float = 0f): Point {
        val r = (sizePx / 2f) - paddingPx
        val f = (min(distanceKm, radiusKm) / radiusKm).toFloat() * r
        val rad = Math.toRadians(bearingDeg)
        return Point(sizePx / 2f + f * sin(rad).toFloat(), sizePx / 2f - f * cos(rad).toFloat())
    }
}
