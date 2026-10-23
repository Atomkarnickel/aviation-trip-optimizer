package com.atomkarnickel.overhead

import com.atomkarnickel.overhead.data.remote.GitHubAssetDto
import com.atomkarnickel.overhead.data.remote.GitHubReleaseDto
import com.atomkarnickel.overhead.data.remote.ReleaseRepository
import com.atomkarnickel.overhead.domain.ReleaseCheck
import com.atomkarnickel.overhead.domain.ReleaseRules
import com.atomkarnickel.overhead.domain.UpdateState
import com.atomkarnickel.overhead.domain.VersionUtils
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val REPO = "Atomkarnickel/aviation-trip-optimizer"
private const val PREFIX = "overhead-"

private fun release(tag: String, draft: Boolean = false, pre: Boolean = false, apk: Boolean = true, host: String = "https://github.com/$REPO") =
    GitHubReleaseDto(
        tagName = tag, draft = draft, prerelease = pre, publishedAt = "2026-10-24T10:00:00Z", htmlUrl = "https://github.com/$REPO/releases/tag/$tag",
        body = "Widget mit Mini-Radar.\n\n## Android-APK",
        assets = if (!apk) emptyList() else listOf(
            GitHubAssetDto("$PREFIX${tag.removePrefix("v")}.apk", "$host/releases/download/$tag/$PREFIX${tag.removePrefix("v")}.apk", 1000),
            GitHubAssetDto("$PREFIX${tag.removePrefix("v")}.apk.sha256", "$host/releases/download/$tag/$PREFIX${tag.removePrefix("v")}.apk.sha256", 90),
        ),
    )

class VersionTest {
    @Test
    fun compareIncludingPrereleases() {
        assertEquals(1, VersionUtils.compare("1.10.0", "1.9.9"))
        assertEquals(0, VersionUtils.compare("v1.2.3", "1.2.3"))
        assertEquals(-1, VersionUtils.compare("1.0.0-beta.2", "1.0.0"))
        assertEquals(-1, VersionUtils.compare("1.0.0-alpha", "1.0.0-alpha.1"))
        assertEquals(1, VersionUtils.compare("1.0.0-beta.11", "1.0.0-beta.2"))
        assertNull(VersionUtils.parse("1.0"))
        assertEquals(10203, VersionUtils.versionCode("1.2.3"))
    }
}

class ReleaseRulesTest {
    @Test
    fun onlyStableReleasesWithOwnApkCount() {
        val list = listOf(
            release("v1.1.0"), release("v1.2.0-rc.1", pre = true), release("v1.3.0", draft = true),
            release("v1.0.9", apk = false), release("v1.0.8", host = "https://evil.example.com"), release("v1.0.0"),
        )
        val stable = ReleaseRules.stableSorted(list, REPO, PREFIX)
        assertEquals(listOf("1.1.0", "1.0.0"), stable.map { it.version })
        assertEquals("Widget mit Mini-Radar.", stable.first().summary)
    }

    @Test
    fun updateStateAndSafeUrl() {
        val ok = ReleaseCheck.Ok(ReleaseRules.stableSorted(listOf(release("v1.1.0")), REPO, PREFIX).first(), emptyList(), 0L)
        assertEquals(UpdateState.UPDATE_AVAILABLE, ReleaseRules.updateState("1.0.0", ok))
        assertEquals(UpdateState.CURRENT, ReleaseRules.updateState("1.1.0", ok))
        assertEquals(UpdateState.NEWER_THAN_RELEASE, ReleaseRules.updateState("1.2.0", ok))
        assertEquals(UpdateState.UNKNOWN, ReleaseRules.updateState("1.0.0", null))
        assertTrue(ReleaseRules.isSafeDownloadUrl("https://github.com/$REPO/releases/download/v1.1.0/overhead-1.1.0.apk", REPO))
        assertFalse(ReleaseRules.isSafeDownloadUrl("http://github.com/$REPO/releases/download/v1.1.0/overhead-1.1.0.apk", REPO))
        assertEquals("ab".repeat(32), ReleaseRules.parseChecksum("${"AB".repeat(32)}  overhead-1.1.0.apk"))
    }

    @Test
    fun repositoryCachesAndFallsBackToStaleCache() = runTest {
        val server = MockWebServer().apply { start() }
        var now = 0L
        val repo = ReleaseRepository(client, REPO, PREFIX, server.url("/").toString()) { now }
        val body = """[{"tag_name":"v1.0.0","published_at":"2026-10-24T10:00:00Z","body":"Erstes Release","assets":[
            {"name":"overhead-1.0.0.apk","browser_download_url":"https://github.com/$REPO/releases/download/v1.0.0/overhead-1.0.0.apk","size":5}]}]"""
        server.enqueue(MockResponse().setBody(body))
        val first = repo.check() as ReleaseCheck.Ok
        assertEquals("1.0.0", first.latest.version)
        assertEquals("/repos/$REPO/releases?per_page=30", server.takeRequest().path)
        repo.check()
        assertEquals(1, server.requestCount) // aus dem Cache
        now += ReleaseRepository.CACHE_MS + 1
        server.enqueue(MockResponse().setResponseCode(503))
        val stale = repo.check() as ReleaseCheck.Ok
        assertTrue(stale.stale)
        server.enqueue(MockResponse().setBody("[]"))
        assertTrue(repo.check(force = true) is ReleaseCheck.NoRelease)
        server.shutdown()
    }
}
