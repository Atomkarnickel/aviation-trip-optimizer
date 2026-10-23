package com.atomkarnickel.overhead.data.remote

import com.atomkarnickel.overhead.domain.Geo
import com.atomkarnickel.overhead.domain.LatLon
import okhttp3.OkHttpClient
import java.util.Locale
import kotlin.math.ceil

/** Eine kostenlose ADS-B-Quelle ohne API-Schlüssel. */
interface AdsbSource {
    val name: String
    val attribution: String
    suspend fun nearby(center: LatLon, radiusKm: Int): ReadsbParser.Result
}

/** Umkreissuche im readsb-Format; [path] baut den Pfad aus Breite, Länge und Radius in Seemeilen. */
class ReadsbSource(
    override val name: String,
    override val attribution: String,
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val path: (lat: String, lon: String, nm: Int) -> String,
) : AdsbSource {
    /**
     * Datenschutz: Der Dienst erfährt den Standort nur auf 0,01° gerundet (≈ 1 km); der Radius wird um diese
     * Unschärfe erweitert. Entfernungen und Filter rechnet die App danach exakt auf dem Gerät.
     */
    override suspend fun nearby(center: LatLon, radiusKm: Int): ReadsbParser.Result {
        val nm = ceil((radiusKm + PRIVACY_MARGIN_KM) / Geo.KM_PER_NM).toInt().coerceIn(1, 250)
        val lat = String.format(Locale.US, "%.2f", center.lat)
        val lon = String.format(Locale.US, "%.2f", center.lon)
        val res = client.getText(baseUrl.trimEnd('/') + path(lat, lon, nm))
        if (!res.ok) throw HttpException(res.code, "$name: HTTP ${res.code}")
        return ReadsbParser.parse(res.body)
    }

    companion object {
        const val PRIVACY_MARGIN_KM = 2.0

        fun adsbLol(client: OkHttpClient, baseUrl: String = "https://api.adsb.lol") = ReadsbSource(
            "adsb.lol", "Flugdaten: adsb.lol (ODbL 1.0)", client, baseUrl,
        ) { lat, lon, nm -> "/v2/point/$lat/$lon/$nm" }

        fun adsbFi(client: OkHttpClient, baseUrl: String = "https://opendata.adsb.fi") = ReadsbSource(
            "adsb.fi", "Flugdaten: adsb.fi Open Data", client, baseUrl,
        ) { lat, lon, nm -> "/api/v2/lat/$lat/lon/$lon/dist/$nm" }
    }
}
