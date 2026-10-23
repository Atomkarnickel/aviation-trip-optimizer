package com.atomkarnickel.overhead.data.remote

import com.atomkarnickel.overhead.domain.RouteAirport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Serializable
data class RouteRecord(
    val callsign: String,
    /** ICAO-Codes der Flughäfen in Flugreihenfolge; leer = keine Route bekannt. */
    val airports: List<String>,
    val updatedEpochSec: Long? = null,
)

@Serializable
private data class HexdbRoute(val flight: String? = null, val route: String? = null, val updatetime: Long? = null)

@Serializable
private data class HexdbAirport(
    val icao: String? = null,
    val iata: String? = null,
    val airport: String? = null,
    @SerialName("region_name") val region: String? = null,
    @SerialName("country_code") val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** Routen- und Flughafendaten von hexdb.io (kostenlos, ohne Schlüssel). 404 = unbekannt. */
class HexdbService(private val client: OkHttpClient, private val baseUrl: String = "https://hexdb.io") {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun route(callsign: String): RouteRecord {
        val res = client.getText("${baseUrl.trimEnd('/')}/api/v1/route/icao/$callsign")
        if (res.code == 404) return RouteRecord(callsign, emptyList())
        if (!res.ok) throw HttpException(res.code, "hexdb.io: HTTP ${res.code}")
        val dto = json.decodeFromString<HexdbRoute>(res.body)
        val airports = dto.route.orEmpty().split("-").map { it.trim().uppercase() }.filter { it.matches(Regex("^[A-Z0-9]{4}$")) }
        return RouteRecord(callsign, if (airports.size >= 2) airports else emptyList(), dto.updatetime)
    }

    suspend fun airport(icao: String): RouteAirport? {
        val res = client.getText("${baseUrl.trimEnd('/')}/api/v1/airport/icao/$icao")
        if (res.code == 404) return null
        if (!res.ok) throw HttpException(res.code, "hexdb.io: HTTP ${res.code}")
        val a = json.decodeFromString<HexdbAirport>(res.body)
        return RouteAirport(
            icao = a.icao ?: icao, iata = a.iata?.takeIf { it.isNotBlank() }, name = a.airport, region = a.region,
            country = a.country, lat = a.latitude, lon = a.longitude,
        )
    }
}
