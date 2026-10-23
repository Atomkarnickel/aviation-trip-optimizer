package com.atomkarnickel.overhead.data

import com.atomkarnickel.overhead.data.remote.AdsbSource
import com.atomkarnickel.overhead.domain.Aircraft
import com.atomkarnickel.overhead.domain.AircraftKind
import com.atomkarnickel.overhead.domain.Geo
import com.atomkarnickel.overhead.domain.Observer
import com.atomkarnickel.overhead.domain.RawAircraft
import com.atomkarnickel.overhead.domain.Snapshot
import kotlinx.coroutines.CancellationException
import java.io.IOException

class FetchException(message: String) : IOException(message)

/** Holt Flugzeuge im Umkreis – bei einem Ausfall automatisch von der nächsten Quelle. */
class AircraftRepository(
    private val sources: List<AdsbSource>,
    private val routes: RouteRepository?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(sources.isNotEmpty())
    }

    val attributions: List<String> get() = sources.map { it.attribution }

    suspend fun fetch(observer: Observer, radiusKm: Int, hideGround: Boolean, withRoutes: Boolean = true): Snapshot {
        val errors = mutableListOf<String>()
        for (source in sources) {
            val raw = try {
                source.nearby(observer.position, radiusKm).aircraft
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += e.message ?: "${source.name}: ${e.javaClass.simpleName}"
                continue
            }
            val aircraft = relativeTo(observer, raw, radiusKm, hideGround)
            val routeMap = if (withRoutes && routes != null) routes.routesFor(aircraft) else emptyMap()
            return Snapshot(observer, clock(), source.name, radiusKm, aircraft, routeMap)
        }
        throw FetchException("Keine Flugdaten erreichbar (${errors.joinToString("; ")})")
    }

    companion object {
        /** Entfernung, Richtung und Höhenwinkel berechnen, auf den Radius beschränken, nach Nähe sortieren. */
        fun relativeTo(observer: Observer, raw: List<RawAircraft>, radiusKm: Int, hideGround: Boolean): List<Aircraft> {
            val o = observer.position
            return raw.asSequence()
                .filter { !hideGround || (!it.onGround && AircraftKind.of(it.category) != AircraftKind.GROUND) }
                .map { r ->
                    val d = Geo.distanceKm(o.lat, o.lon, r.lat, r.lon)
                    Aircraft(
                        raw = r,
                        distanceKm = d,
                        bearingDeg = Geo.bearingDeg(o.lat, o.lon, r.lat, r.lon),
                        elevationDeg = when {
                            r.onGround -> null
                            r.altitudeFt != null -> Geo.elevationDeg(d, r.altitudeFt * Geo.M_PER_FT)
                            else -> null
                        },
                    )
                }
                .filter { it.distanceKm <= radiusKm }
                .distinctBy { it.hex }
                .sortedBy { it.distanceKm }
                .toList()
        }
    }
}
