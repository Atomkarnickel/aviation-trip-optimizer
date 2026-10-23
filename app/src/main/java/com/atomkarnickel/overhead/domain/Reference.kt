package com.atomkarnickel.overhead.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class TypeFile(val source: String, val types: Map<String, String>)

@Serializable
private data class AirlineFile(val source: String, val airlines: Map<String, String>)

/** Nachschlagetabellen aus den Assets (aircraft_types.json, airlines.json). */
class ReferenceData(private val types: Map<String, String>, private val airlines: Map<String, String>) {

    /** Lesbarer Typname: Tabelle → Beschreibung des Dienstes → ICAO-Code → null. */
    fun typeName(code: String?, description: String? = null): String? {
        val c = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        return c?.let { types[it] } ?: description?.let(::prettyDescription) ?: c
    }

    /** Airline anhand der ersten drei Buchstaben eines Linien-Rufzeichens (z. B. SWR123 → SWISS). */
    fun airlineName(callsign: String?): String? {
        val cs = callsign?.trim()?.uppercase() ?: return null
        if (!AIRLINE_CALLSIGN.matches(cs)) return null
        return airlines[cs.take(3)]
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Linien-Rufzeichen: 3 Buchstaben ICAO-Kennung + Flugnummer (mit Ziffer beginnend). */
        val AIRLINE_CALLSIGN = Regex("^[A-Z]{3}[0-9][0-9A-Z]{0,3}$")

        fun parse(typesJson: String, airlinesJson: String) = ReferenceData(
            json.decodeFromString<TypeFile>(typesJson).types,
            json.decodeFromString<AirlineFile>(airlinesJson).airlines,
        )

        val EMPTY = ReferenceData(emptyMap(), emptyMap())

        /** „AIRBUS A-321neo“ → „Airbus A-321neo“ (Großbuchstaben-Herstellernamen glätten). */
        fun prettyDescription(desc: String): String? {
            val d = desc.trim().takeIf { it.isNotEmpty() } ?: return null
            return d.split(" ").joinToString(" ") { word ->
                if (word.length > 2 && word.all { it.isLetter() && it.isUpperCase() }) word.lowercase().replaceFirstChar { it.uppercase() } else word
            }
        }
    }
}

/** Grobe Einordnung über die ADS-B-Emitterkategorie (DO-260B). */
enum class AircraftKind(val label: String, val symbol: String) {
    LIGHT("Kleinflugzeug", "🛩"),
    AIRLINER("Verkehrsflugzeug", "✈"),
    HEAVY("Großraumflugzeug", "✈"),
    HELICOPTER("Hubschrauber", "🚁"),
    GLIDER("Segelflugzeug", "🪂"),
    BALLOON("Ballon/Luftschiff", "🎈"),
    DRONE("Drohne", "◈"),
    GROUND("Bodenfahrzeug", "▪"),
    UNKNOWN("Luftfahrzeug", "✈");

    companion object {
        fun of(category: String?): AircraftKind = when (category?.uppercase()) {
            "A1", "A2" -> LIGHT
            "A3", "A4" -> AIRLINER
            "A5" -> HEAVY
            "A7" -> HELICOPTER
            "B1", "B4" -> GLIDER
            "B2" -> BALLOON
            "B6" -> DRONE
            "C1", "C2", "C3" -> GROUND
            else -> UNKNOWN
        }
    }
}
