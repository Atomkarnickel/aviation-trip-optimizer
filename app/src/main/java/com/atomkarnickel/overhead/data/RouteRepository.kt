package com.atomkarnickel.overhead.data

import com.atomkarnickel.overhead.data.remote.HexdbService
import com.atomkarnickel.overhead.data.remote.RouteRecord
import com.atomkarnickel.overhead.domain.Aircraft
import com.atomkarnickel.overhead.domain.Geo
import com.atomkarnickel.overhead.domain.LatLon
import com.atomkarnickel.overhead.domain.ReferenceData
import com.atomkarnickel.overhead.domain.Route
import com.atomkarnickel.overhead.domain.RouteAirport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Zwischenspeicher für Routen (mit Ablauf) und Flughäfen (dauerhaft). */
interface RouteCache {
    fun route(callsign: String): CachedRoute?
    fun putRoute(route: CachedRoute)
    fun airport(icao: String): RouteAirport?
    fun putAirport(airport: RouteAirport)
    suspend fun flush() {}
}

@Serializable
data class CachedRoute(val record: RouteRecord, val savedAtMillis: Long)

class MemoryRouteCache : RouteCache {
    val routes = HashMap<String, CachedRoute>()
    val airports = HashMap<String, RouteAirport>()
    override fun route(callsign: String) = routes[callsign]
    override fun putRoute(route: CachedRoute) { routes[route.record.callsign] = route }
    override fun airport(icao: String) = airports[icao]
    override fun putAirport(airport: RouteAirport) { airports[airport.icao] = airport }
}

/**
 * Löst Rufzeichen in Routen auf. Hält sich bewusst zurück: nur Linien-Rufzeichen, höchstens [maxLookups]
 * neue Abfragen pro Aktualisierung, bekannte Routen 12 h und unbekannte 6 h im Cache.
 */
class RouteRepository(
    private val hexdb: HexdbService,
    private val cache: RouteCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun routesFor(aircraft: List<Aircraft>, maxRoutes: Int = 8, maxLookups: Int = 4): Map<String, Route> {
        val result = LinkedHashMap<String, Route>()
        var lookups = 0
        val candidates = aircraft.asSequence()
            .filter { !it.raw.onGround }
            .mapNotNull { a -> a.raw.callsign?.takeIf { ReferenceData.AIRLINE_CALLSIGN.matches(it) }?.let { it to a } }
            .distinctBy { it.first }
            .take(maxRoutes)
        for ((callsign, plane) in candidates) {
            val record = cachedRecord(callsign) ?: if (lookups < maxLookups) {
                lookups++
                fetchRecord(callsign)
            } else null
            if (record == null || record.airports.isEmpty()) continue
            val airports = record.airports.map { icao -> resolveAirport(icao) }
            result[callsign] = Route(
                callsign = callsign,
                airports = airports,
                plausible = Geo.routePlausible(airports.map { a -> a.lat?.let { lat -> a.lon?.let { lon -> LatLon(lat, lon) } } }, plane.position),
                updatedEpochSec = record.updatedEpochSec,
            )
        }
        cache.flush()
        return result
    }

    private fun cachedRecord(callsign: String): RouteRecord? {
        val c = cache.route(callsign) ?: return null
        val ttl = if (c.record.airports.isEmpty()) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
        return c.record.takeIf { clock() - c.savedAtMillis < ttl }
    }

    private suspend fun fetchRecord(callsign: String): RouteRecord? = try {
        hexdb.route(callsign).also { cache.putRoute(CachedRoute(it, clock())) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // Routen sind Zusatzinformation – Fehler blockieren die Anzeige nicht.
    }

    private suspend fun resolveAirport(icao: String): RouteAirport {
        cache.airport(icao)?.let { return it }
        val fetched = try {
            hexdb.airport(icao)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RouteAirport(icao) // nicht cachen – beim nächsten Mal erneut versuchen
        }
        return (fetched ?: RouteAirport(icao)).also { cache.putAirport(it) }
    }

    companion object {
        const val POSITIVE_TTL_MS = 12 * 3600_000L
        const val NEGATIVE_TTL_MS = 6 * 3600_000L
    }
}
