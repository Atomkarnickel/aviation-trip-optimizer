package com.atomkarnickel.overhead.domain

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Kugelgeometrie für Beobachter ↔ Flugzeug (Großkreis, mittlerer Erdradius). */
object Geo {
    const val EARTH_RADIUS_KM = 6371.0
    const val KM_PER_NM = 1.852
    const val M_PER_FT = 0.3048

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Anfangskurs vom Beobachter zum Ziel, 0° = Norden, im Uhrzeigersinn, Bereich [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Höhenwinkel über dem Horizont in Grad: 90° = senkrecht über dir, 0° = am Horizont.
     * Berücksichtigt die Erdkrümmung (Absenkung d²/2R), ohne Refraktion.
     */
    fun elevationDeg(groundDistanceKm: Double, altitudeM: Double, observerAltitudeM: Double = 0.0): Double {
        val heightKm = (altitudeM - observerAltitudeM) / 1000.0
        val drop = groundDistanceKm * groundDistanceKm / (2 * EARTH_RADIUS_KM)
        return Math.toDegrees(atan2(heightKm - drop, groundDistanceKm))
    }

    /** Punkt in gegebener Richtung und Entfernung (für Radius-Kreise auf der Karte). */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceKm: Double): LatLon {
        val d = distanceKm / EARTH_RADIUS_KM
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), (Math.toDegrees(l2) + 540.0) % 360.0 - 180.0)
    }

    private val COMPASS = listOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
    private val ARROWS = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

    /** Himmelsrichtung (8 Sektoren, deutsch). */
    fun compass(bearingDeg: Double): String = COMPASS[sector(bearingDeg)]

    /** Pfeil in Blickrichtung (Norden = oben). */
    fun arrow(bearingDeg: Double): String = ARROWS[sector(bearingDeg)]

    private fun sector(deg: Double): Int = (((deg % 360 + 360) % 360) / 45.0).roundToInt() % 8

    /**
     * Ist das Flugzeug plausibel auf dieser Route unterwegs? Routen aus Rufzeichen-Datenbanken können veraltet sein.
     * Plausibel, wenn der Umweg über die aktuelle Position auf irgendeinem Teilstück höchstens 15 % + 100 km beträgt
     * (Spielraum für Abflug-/Anflugverfahren und Luftstraßen). Null, wenn Koordinaten fehlen.
     */
    fun routePlausible(stops: List<LatLon?>, position: LatLon): Boolean? {
        if (stops.size < 2 || stops.any { it == null }) return null
        val points = stops.filterNotNull()
        return points.zipWithNext().any { (a, b) ->
            val direct = distanceKm(a.lat, a.lon, b.lat, b.lon)
            val via = distanceKm(a.lat, a.lon, position.lat, position.lon) + distanceKm(position.lat, position.lon, b.lat, b.lon)
            via <= direct * 1.15 + 100.0
        }
    }
}
