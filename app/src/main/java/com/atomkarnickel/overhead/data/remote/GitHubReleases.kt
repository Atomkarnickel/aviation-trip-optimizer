package com.atomkarnickel.overhead.data.remote

import com.atomkarnickel.overhead.domain.ReleaseCheck
import com.atomkarnickel.overhead.domain.ReleaseRules
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Serializable
data class GitHubAssetDto(
    val name: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    val size: Long? = null,
)

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val body: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

/** Versionsprüfung über die öffentliche GitHub-API (ohne Anmeldung, 60 Abfragen/h je IP – daher Cache). */
class ReleaseRepository(
    private val client: OkHttpClient,
    private val repository: String,
    private val apkPrefix: String,
    private val baseUrl: String = "https://api.github.com",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cached: ReleaseCheck.Ok? = null

    suspend fun check(force: Boolean = false): ReleaseCheck {
        val now = clock()
        cached?.takeIf { !force && now - it.checkedAtMillis < CACHE_MS }?.let { return it }
        return try {
            val res = client.getText("${baseUrl.trimEnd('/')}/repos/$repository/releases?per_page=30")
            if (!res.ok) throw HttpException(res.code, "GitHub: HTTP ${res.code}")
            val releases = ReleaseRules.stableSorted(json.decodeFromString<List<GitHubReleaseDto>>(res.body), repository, apkPrefix)
            if (releases.isEmpty()) ReleaseCheck.NoRelease("Noch keine stabile APK veröffentlicht.", now)
            else ReleaseCheck.Ok(releases.first(), releases, now).also { cached = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached?.copy(stale = true) ?: ReleaseCheck.Unavailable("GitHub nicht erreichbar (${e.message ?: e.javaClass.simpleName}).", now)
        }
    }

    suspend fun checksum(url: String): String? = try {
        client.getText(url).takeIf { it.ok }?.let { ReleaseRules.parseChecksum(it.body) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val CACHE_MS = 15 * 60_000L
    }
}
