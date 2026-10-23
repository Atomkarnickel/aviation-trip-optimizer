package com.atomkarnickel.overhead.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AltitudeUnit { METERS, FEET }

/** Deutsche Anzeigeformate (Tausendertrenner: schmales Leerzeichen). */
object Format {
    private const val THIN_SPACE = ' '
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun thousands(n: Int): String {
        val s = abs(n).toString().reversed().chunked(3).joinToString(THIN_SPACE.toString()).reversed()
        return if (n < 0) "−$s" else s
    }

    fun altitude(feet: Int?, onGround: Boolean, unit: AltitudeUnit): String = when {
        onGround -> "am Boden"
        feet == null -> "Höhe ?"
        unit == AltitudeUnit.FEET -> "${thousands(feet)} ft"
        else -> "${thousands(((feet * Geo.M_PER_FT) / 10).roundToInt() * 10)} m"
    }

    fun distance(km: Double): String = when {
        km < 10 -> String.format(Locale.GERMANY, "%.1f km", km)
        else -> "${km.roundToInt()} km"
    }

    fun speed(knots: Double?): String = knots?.let { "${(it * Geo.KM_PER_NM).roundToInt()} km/h" } ?: "–"

    /** Steig-/Sinkrate als Pfeil (Schwelle 300 ft/min). */
    fun climbArrow(fpm: Int?): String = when {
        fpm == null -> ""
        fpm > 300 -> "↗"
        fpm < -300 -> "↘"
        else -> "→"
    }

    fun verticalRate(fpm: Int?): String = when {
        fpm == null -> "–"
        abs(fpm) < 100 -> "Reiseflug"
        fpm > 0 -> "steigt ${(fpm * Geo.M_PER_FT / 60).let { String.format(Locale.GERMANY, "%.1f", it) }} m/s"
        else -> "sinkt ${(-fpm * Geo.M_PER_FT / 60).let { String.format(Locale.GERMANY, "%.1f", it) }} m/s"
    }

    fun elevation(deg: Double?): String = when {
        deg == null -> "–"
        deg < 0 -> "unter dem Horizont"
        else -> "${deg.roundToInt()}° über dem Horizont"
    }

    fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** „gerade eben“, „vor 3 min“, „vor 2 h“. */
    fun age(millis: Long, now: Long): String {
        val s = ((now - millis) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "gerade eben"
            s < 3600 -> "vor ${s / 60} min"
            else -> "vor ${s / 3600} h"
        }
    }
}
