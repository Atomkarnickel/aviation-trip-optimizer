package com.atomkarnickel.aviationoptimizer.domain

/**
 * Semantic Versioning – identische Regeln wie backend/app/services/releases.py und apps/web/src/lib/semver.ts.
 * Getestet gegen die gemeinsamen Testvektoren in shared/test-vectors/semver.json.
 */
object VersionUtils {
    data class Parsed(val major: Int, val minor: Int, val patch: Int, val pre: String?)

    private val SEMVER = Regex("""^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?$""")

    fun parse(value: String?): Parsed? {
        val m = SEMVER.matchEntire(value?.trim() ?: return null) ?: return null
        return Parsed(
            m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
            m.groupValues[4].ifEmpty { null },
        )
    }

    /** -1, 0 oder 1. Wirft [IllegalArgumentException] bei ungültigen Versionen. */
    fun compare(a: String, b: String): Int {
        val pa = parse(a) ?: throw IllegalArgumentException("Ungültige Version: $a")
        val pb = parse(b) ?: throw IllegalArgumentException("Ungültige Version: $b")
        listOf(pa.major to pb.major, pa.minor to pb.minor, pa.patch to pb.patch).forEach { (x, y) ->
            if (x != y) return if (x > y) 1 else -1
        }
        return comparePre(pa.pre, pb.pre)
    }

    private fun comparePre(a: String?, b: String?): Int {
        if (a == b) return 0
        if (a == null) return 1
        if (b == null) return -1
        val pa = a.split(".")
        val pb = b.split(".")
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            if (x == y) continue
            val xn = x.all { it.isDigit() }
            val yn = y.all { it.isDigit() }
            if (xn && yn) return if (x.toLong() > y.toLong()) 1 else -1
            if (xn != yn) return if (xn) -1 else 1
            return if (x > y) 1 else -1
        }
        return pa.size.compareTo(pb.size).coerceIn(-1, 1)
    }

    fun versionCode(version: String): Int {
        val p = parse(version) ?: throw IllegalArgumentException("Ungültige Version: $version")
        require(p.pre == null) { "Nur stabile Versionen" }
        require(p.minor < 100 && p.patch < 100) { "MINOR und PATCH müssen < 100 sein" }
        return p.major * 10000 + p.minor * 100 + p.patch
    }
}
