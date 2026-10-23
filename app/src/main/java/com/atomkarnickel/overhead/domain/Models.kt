package com.atomkarnickel.overhead.domain

import kotlinx.serialization.Serializable

@Serializable
data class LatLon(val lat: Double, val lon: Double)

/** Rohdaten eines Flugzeugs, wie sie ein ADS-B-Dienst liefert (noch ohne Bezug zum Beobachter). */
@Serializable
data class RawAircraft(
    val hex: String,
    val callsign: String? = null,
    val registration: String? = null,
    val typeCode: String? = null,
    val typeDescription: String? = null,
    val category: String? = null,
    val lat: Double,
    val lon: Double,
    val altitudeFt: Int? = null,
    val onGround: Boolean = false,
    val groundSpeedKt: Double? = null,
    val trackDeg: Double? = null,
    val verticalRateFpm: Int? = null,
    val squawk: String? = null,
    val emergency: String? = null,
    val seenPosSec: Double? = null,
)

/** Flugzeug relativ zum Beobachter: Entfernung, Richtung, Höhenwinkel. */
@Serializable
data class Aircraft(
    val raw: RawAircraft,
    val distanceKm: Double,
    val bearingDeg: Double,
    val elevationDeg: Double?,
) {
    val hex get() = raw.hex
    val position get() = LatLon(raw.lat, raw.lon)
}

@Serializable
data class RouteAirport(
    val icao: String,
    val iata: String? = null,
    val name: String? = null,
    val region: String? = null,
    val country: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    val shortCode get() = iata?.takeIf { it.isNotBlank() } ?: icao
}

/**
 * Route laut Rufzeichen-Datenbank (hexdb.io). [plausible] = passt die aktuelle Position dazu?
 * Null = nicht prüfbar (Koordinaten fehlen).
 */
@Serializable
data class Route(
    val callsign: String,
    val airports: List<RouteAirport>,
    val plausible: Boolean?,
    val updatedEpochSec: Long? = null,
) {
    val label get() = airports.joinToString(" → ") { it.shortCode }
}

enum class LocationSource { DEVICE, FIXED, SAVED }

@Serializable
data class Observer(
    val position: LatLon,
    val source: LocationSource,
    val locationTimeMillis: Long,
)

/** Ein vollständiger Abruf – Grundlage für Liste, Karte und Widget. */
@Serializable
data class Snapshot(
    val observer: Observer,
    val fetchedAtMillis: Long,
    val source: String,
    val radiusKm: Int,
    val aircraft: List<Aircraft>,
    val routes: Map<String, Route> = emptyMap(),
    val error: String? = null,
) {
    fun routeFor(aircraft: Aircraft): Route? = aircraft.raw.callsign?.let { routes[it] }
}
