package com.atomkarnickel.aviationoptimizer.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Eingaben der Suchmaske (Rohwerte aus der UI). */
data class SearchForm(
    val origins: String = "ZRH",
    val destinations: String = "",
    val earliestDeparture: String = LocalDate.now().plusDays(60).toString(),
    val latestReturn: String = LocalDate.now().plusDays(79).toString(),
    val minStay: String = "5",
    val maxStay: String = "14",
    val adults: String = "1",
    val maxBudget: String = "",
    val maxStops: Int = 1,
    val maxTravelHours: String = "",
    val desiredAircraft: Set<String> = emptySet(),
    val cabinClass: String = "economy",
    val requireCheckedBag: Boolean = false,
    val maxDateCombinations: Int = 6,
)

enum class SearchField { ORIGINS, DESTINATIONS, EARLIEST, LATEST, MIN_STAY, MAX_STAY, ADULTS, BUDGET, TRAVEL_HOURS }

/** Spiegelt die Validierung aus backend/app/models/schemas.py (SearchRequest). */
object SearchValidation {
    private val AIRPORT = Regex("^[A-Z]{3}$")

    fun parseCodes(value: String): List<String> =
        value.split(Regex("[\\s,;]+")).map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()

    fun validate(form: SearchForm): Map<SearchField, String> {
        val e = linkedMapOf<SearchField, String>()
        val origins = parseCodes(form.origins)
        val destinations = parseCodes(form.destinations)
        when {
            origins.isEmpty() -> e[SearchField.ORIGINS] = "Mindestens einen Abflughafen angeben (IATA, z. B. ZRH)."
            origins.size > 5 -> e[SearchField.ORIGINS] = "Höchstens 5 Abflughäfen."
            origins.any { !AIRPORT.matches(it) } -> e[SearchField.ORIGINS] = "Ungültiger IATA-Code (3 Buchstaben)."
        }
        when {
            destinations.size > 10 -> e[SearchField.DESTINATIONS] = "Höchstens 10 Ziele."
            destinations.any { !AIRPORT.matches(it) } -> e[SearchField.DESTINATIONS] = "Ungültiger IATA-Code (3 Buchstaben)."
            destinations.any { it in origins } -> e[SearchField.DESTINATIONS] = "Ziel darf nicht Abflughafen sein."
        }
        val earliest = runCatching { LocalDate.parse(form.earliestDeparture) }.getOrNull()
        val latest = runCatching { LocalDate.parse(form.latestReturn) }.getOrNull()
        if (earliest == null) e[SearchField.EARLIEST] = "Datum im Format JJJJ-MM-TT."
        if (latest == null) e[SearchField.LATEST] = "Datum im Format JJJJ-MM-TT."
        val minStay = form.minStay.toIntOrNull()
        val maxStay = form.maxStay.toIntOrNull()
        if (minStay == null || minStay !in 1..60) e[SearchField.MIN_STAY] = "1–60 Tage."
        if (maxStay == null || maxStay !in 1..60) e[SearchField.MAX_STAY] = "1–60 Tage."
        else if (minStay != null && minStay > maxStay) e[SearchField.MAX_STAY] = "Maximum muss ≥ Minimum sein."
        if (earliest != null && latest != null) {
            val span = ChronoUnit.DAYS.between(earliest, latest)
            when {
                span <= 0 -> e[SearchField.LATEST] = "Rückflug muss nach dem Abflug liegen."
                span > 120 -> e[SearchField.LATEST] = "Zeitraum höchstens 120 Tage."
                minStay != null && minStay > span -> e[SearchField.MIN_STAY] = "Minimale Reisedauer passt nicht in den Zeitraum."
            }
        }
        if (form.adults.toIntOrNull()?.let { it in 1..9 } != true) e[SearchField.ADULTS] = "1–9 Reisende."
        if (form.maxBudget.isNotBlank() && (form.maxBudget.toDoubleOrNull() ?: -1.0) <= 0) e[SearchField.BUDGET] = "Positive Zahl."
        if (form.maxTravelHours.isNotBlank() && (form.maxTravelHours.toDoubleOrNull()?.let { it > 0 && it <= 60 } != true)) {
            e[SearchField.TRAVEL_HOURS] = "0–60 Stunden."
        }
        return e
    }

    /** Ordnet Backend-Feldnamen (422-Antwort) den Formularfeldern zu. */
    fun fieldFromBackend(field: String): SearchField? = when (field.substringBefore('.')) {
        "origins" -> SearchField.ORIGINS
        "destinations" -> SearchField.DESTINATIONS
        "earliest_departure" -> SearchField.EARLIEST
        "latest_return" -> SearchField.LATEST
        "min_stay_days" -> SearchField.MIN_STAY
        "max_stay_days" -> SearchField.MAX_STAY
        "adults" -> SearchField.ADULTS
        "max_budget" -> SearchField.BUDGET
        "max_travel_hours" -> SearchField.TRAVEL_HOURS
        else -> null
    }
}
