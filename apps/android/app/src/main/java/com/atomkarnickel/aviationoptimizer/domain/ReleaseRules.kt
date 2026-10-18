package com.atomkarnickel.aviationoptimizer.domain

import com.atomkarnickel.aviationoptimizer.data.remote.GitHubReleaseDto

data class ApkRelease(
    val version: String,
    val tag: String,
    val publishedAt: String,
    val htmlUrl: String,
    val summary: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long?,
    val checksumUrl: String?,
)

sealed interface ReleaseCheck {
    val checkedAtMillis: Long

    data class Ok(val latest: ApkRelease, val releases: List<ApkRelease>, override val checkedAtMillis: Long, val stale: Boolean = false) : ReleaseCheck
    data class NoRelease(val message: String, override val checkedAtMillis: Long) : ReleaseCheck
    data class Unavailable(val message: String, override val checkedAtMillis: Long) : ReleaseCheck
}

enum class UpdateState { CURRENT, UPDATE_AVAILABLE, NEWER_THAN_RELEASE, UNKNOWN }

/** Regeln aus shared/RELEASE_METADATA.md – identisch zu Backend und Web. */
object ReleaseRules {
    fun parseStable(raw: GitHubReleaseDto, repository: String, prefix: String): ApkRelease? {
        if (raw.draft || raw.prerelease) return null
        val tag = raw.tagName ?: return null
        val parsed = VersionUtils.parse(tag) ?: return null
        if (!tag.startsWith("v") || parsed.pre != null) return null
        val published = raw.publishedAt ?: return null
        val version = tag.removePrefix("v")
        val allowed = "https://github.com/$repository/releases/download/"
        val apk = raw.assets.firstOrNull {
            it.name == "$prefix$version.apk" && it.browserDownloadUrl?.startsWith(allowed) == true
        } ?: return null
        val checksum = raw.assets.firstOrNull {
            it.name == "$prefix$version.apk.sha256" && it.browserDownloadUrl?.startsWith(allowed) == true
        }
        val htmlUrl = raw.htmlUrl?.takeIf { it.startsWith("https://github.com/") }
            ?: "https://github.com/$repository/releases/tag/$tag"
        return ApkRelease(
            version = version, tag = tag, publishedAt = published, htmlUrl = htmlUrl,
            summary = summarize(raw.body ?: ""), apkName = apk.name!!, apkUrl = apk.browserDownloadUrl!!,
            apkSize = apk.size, checksumUrl = checksum?.browserDownloadUrl,
        )
    }

    fun stableSorted(raw: List<GitHubReleaseDto>, repository: String, prefix: String): List<ApkRelease> =
        raw.mapNotNull { parseStable(it, repository, prefix) }.sortedWith { a, b -> VersionUtils.compare(b.version, a.version) }

    fun summarize(body: String, limit: Int = 160): String {
        for (line in body.lines()) {
            val text = line.trim().trimStart('#', '*', '-', '>', ' ').trim()
            val lower = text.lowercase()
            if (text.isNotEmpty() && !lower.startsWith("what's changed") && !lower.startsWith("full changelog") && !lower.startsWith("sha")) {
                return if (text.length <= limit) text else text.take(limit - 1).trimEnd() + "…"
            }
        }
        return ""
    }

    fun updateState(installed: String, check: ReleaseCheck?): UpdateState {
        if (check !is ReleaseCheck.Ok) return UpdateState.UNKNOWN
        return try {
            when (VersionUtils.compare(installed, check.latest.version)) {
                0 -> UpdateState.CURRENT
                -1 -> UpdateState.UPDATE_AVAILABLE
                else -> UpdateState.NEWER_THAN_RELEASE
            }
        } catch (e: IllegalArgumentException) {
            UpdateState.UNKNOWN
        }
    }

    /** Nur HTTPS-Downloads vom eigenen Repository dürfen geöffnet werden. */
    fun isSafeDownloadUrl(url: String, repository: String): Boolean =
        url.startsWith("https://github.com/$repository/releases/download/") && url.endsWith(".apk")

    private val SHA256 = Regex("""\b([a-fA-F0-9]{64})\b""")

    fun parseChecksum(text: String): String? = SHA256.find(text)?.groupValues?.get(1)?.lowercase()
}
