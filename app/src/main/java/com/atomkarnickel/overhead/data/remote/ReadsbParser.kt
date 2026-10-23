package com.atomkarnickel.overhead.data.remote

import com.atomkarnickel.overhead.domain.RawAircraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * Parser für das readsb-„v2“-Format, das adsb.lol und adsb.fi liefern.
 * adsb.lol: Liste unter "ac", "now" in Millisekunden. adsb.fi: Liste unter "aircraft", "now" in Sekunden.
 */
object ReadsbParser {
    data class Result(val aircraft: List<RawAircraft>, val nowMillis: Long?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Positionen älter als [maxPositionAgeSec] werden verworfen – sie zeigen nicht mehr, wo das Flugzeug ist. */
    fun parse(body: String, maxPositionAgeSec: Double = 60.0): Result {
        val root = json.parseToJsonElement(body).jsonObject
        val list = (root["ac"] ?: root["aircraft"]) as? JsonArray ?: JsonArray(emptyList())
        val now = (root["now"] as? JsonPrimitive)?.doubleOrNull?.let { if (it > 1e11) it.toLong() else (it * 1000).toLong() }
        val aircraft = list.mapNotNull { (it as? JsonObject)?.let { o -> parseAircraft(o, maxPositionAgeSec) } }
        return Result(aircraft, now)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun parseAircraft(o: JsonObject, maxAge: Double): RawAircraft? {
        val hex = o.str("hex")?.lowercase() ?: return null
        val lat = o.num("lat") ?: return null
        val lon = o.num("lon") ?: return null
        val seenPos = o.num("seen_pos")
        if (seenPos != null && seenPos > maxAge) return null
        val altBaro = o["alt_baro"] as? JsonPrimitive
        val onGround = altBaro?.isString == true && altBaro.content.equals("ground", ignoreCase = true)
        val altitude = if (onGround) null else (altBaro?.takeIf { !it.isString }?.doubleOrNull ?: o.num("alt_geom"))?.roundToInt()
        return RawAircraft(
            hex = hex,
            callsign = o.str("flight")?.uppercase(),
            registration = o.str("r"),
            typeCode = o.str("t")?.uppercase(),
            typeDescription = o.str("desc"),
            category = o.str("category"),
            lat = lat,
            lon = lon,
            altitudeFt = altitude,
            onGround = onGround,
            groundSpeedKt = o.num("gs"),
            trackDeg = o.num("track") ?: o.num("true_heading") ?: o.num("mag_heading"),
            verticalRateFpm = (o.num("baro_rate") ?: o.num("geom_rate"))?.roundToInt(),
            squawk = o.str("squawk"),
            emergency = o.str("emergency")?.takeIf { it != "none" },
            seenPosSec = seenPos,
        )
    }
}
