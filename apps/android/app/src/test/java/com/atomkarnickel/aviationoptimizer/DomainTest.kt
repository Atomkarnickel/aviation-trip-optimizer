package com.atomkarnickel.aviationoptimizer

import com.atomkarnickel.aviationoptimizer.data.isAllowedBackendUrl
import com.atomkarnickel.aviationoptimizer.data.remote.GitHubAssetDto
import com.atomkarnickel.aviationoptimizer.data.remote.GitHubReleaseDto
import com.atomkarnickel.aviationoptimizer.domain.ReleaseCheck
import com.atomkarnickel.aviationoptimizer.domain.ReleaseRules
import com.atomkarnickel.aviationoptimizer.domain.SearchField
import com.atomkarnickel.aviationoptimizer.domain.SearchForm
import com.atomkarnickel.aviationoptimizer.domain.SearchValidation
import com.atomkarnickel.aviationoptimizer.domain.UpdateState
import com.atomkarnickel.aviationoptimizer.domain.VersionUtils
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

const val REPO = "Atomkarnickel/aviation-trip-optimizer"
const val DL = "https://github.com/$REPO/releases/download"

fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, withApk: Boolean = true, url: String? = null): GitHubReleaseDto {
    val v = tag.removePrefix("v")
    return GitHubReleaseDto(
        tagName = tag, draft = draft, prerelease = prerelease, publishedAt = "2026-10-01T10:00:00Z",
        htmlUrl = "https://github.com/$REPO/releases/tag/$tag", body = "## What's Changed\n- Neue Suche",
        assets = if (!withApk) emptyList() else listOf(
            GitHubAssetDto("aviation-optimizer-$v.apk", url ?: "$DL/$tag/aviation-optimizer-$v.apk", 1024),
            GitHubAssetDto("aviation-optimizer-$v.apk.sha256", "$DL/$tag/aviation-optimizer-$v.apk.sha256", 90),
        ),
    )
}

class VersionUtilsTest {
    private val vectors = testJson.parseToJsonElement(sharedFile("test-vectors/semver.json").readText()).jsonObject

    @Test
    fun compareMatchesSharedVectors() {
        vectors["compare"]!!.jsonArray.forEach {
            val o = it.jsonObject
            val a = o["a"]!!.jsonPrimitive.content
            val b = o["b"]!!.jsonPrimitive.content
            val r = o["result"]!!.jsonPrimitive.int
            assertEquals("$a vs $b", r, VersionUtils.compare(a, b))
            assertEquals("$b vs $a", -r, VersionUtils.compare(b, a))
        }
    }

    @Test
    fun invalidVersionsRejected() {
        vectors["invalid"]!!.jsonArray.forEach {
            val v = it.jsonPrimitive.content
            assertNull(v, VersionUtils.parse(v))
            assertTrue(runCatching { VersionUtils.compare(v, "1.0.0") }.isFailure)
        }
    }

    @Test
    fun versionCodesMatchGradleFormula() {
        vectors["versionCode"]!!.jsonArray.forEach {
            val o = it.jsonObject
            assertEquals(o["code"]!!.jsonPrimitive.int, VersionUtils.versionCode(o["version"]!!.jsonPrimitive.content))
        }
        assertEquals(BuildConfig.VERSION_CODE, VersionUtils.versionCode(BuildConfig.VERSION_NAME.removeSuffix("-debug")))
    }
}

class ReleaseRulesTest {
    @Test
    fun onlyStableReleasesWithOwnApk() {
        assertNotNull(ReleaseRules.parseStable(release("v1.2.0"), REPO, "aviation-optimizer-"))
        assertNull(ReleaseRules.parseStable(release("v1.2.0", draft = true), REPO, "aviation-optimizer-"))
        assertNull(ReleaseRules.parseStable(release("v1.2.0", prerelease = true), REPO, "aviation-optimizer-"))
        assertNull(ReleaseRules.parseStable(release("v1.3.0-rc.1"), REPO, "aviation-optimizer-"))
        assertNull(ReleaseRules.parseStable(release("v1.2.0", withApk = false), REPO, "aviation-optimizer-"))
        assertNull(ReleaseRules.parseStable(release("v1.2.0", url = "https://evil.example/aviation-optimizer-1.2.0.apk"), REPO, "aviation-optimizer-"))
    }

    @Test
    fun sortsBySemverNotAlphabetically() {
        val sorted = ReleaseRules.stableSorted(listOf(release("v1.2.0"), release("v1.10.0"), release("v1.9.9")), REPO, "aviation-optimizer-")
        assertEquals(listOf("1.10.0", "1.9.9", "1.2.0"), sorted.map { it.version })
        assertEquals("Neue Suche", sorted.first().summary)
    }

    @Test
    fun updateStates() {
        val latest = ReleaseRules.stableSorted(listOf(release("v1.2.0")), REPO, "aviation-optimizer-")
        val ok = ReleaseCheck.Ok(latest.first(), latest, 0)
        assertEquals(UpdateState.UPDATE_AVAILABLE, ReleaseRules.updateState("1.1.0", ok))
        assertEquals(UpdateState.CURRENT, ReleaseRules.updateState("1.2.0", ok))
        assertEquals(UpdateState.NEWER_THAN_RELEASE, ReleaseRules.updateState("1.3.0", ok))
        assertEquals(UpdateState.UNKNOWN, ReleaseRules.updateState("1.0.0-debug-kaputt!", ok))
        assertEquals(UpdateState.UNKNOWN, ReleaseRules.updateState("1.0.0", ReleaseCheck.Unavailable("offline", 0)))
        assertEquals(UpdateState.UNKNOWN, ReleaseRules.updateState("1.0.0", null))
    }

    @Test
    fun downloadLinkHandling() {
        assertTrue(ReleaseRules.isSafeDownloadUrl("$DL/v1.2.0/aviation-optimizer-1.2.0.apk", REPO))
        assertFalse(ReleaseRules.isSafeDownloadUrl("http://github.com/$REPO/releases/download/v1.2.0/a.apk", REPO))
        assertFalse(ReleaseRules.isSafeDownloadUrl("https://github.com/other/repo/releases/download/v1/a.apk", REPO))
        assertFalse(ReleaseRules.isSafeDownloadUrl("$DL/v1.2.0/notes.txt", REPO))
        assertEquals("a".repeat(64), ReleaseRules.parseChecksum("${"A".repeat(64)}  aviation-optimizer-1.2.0.apk"))
        assertNull(ReleaseRules.parseChecksum("kaputt"))
    }
}

class SearchValidationTest {
    private val valid = SearchForm(origins = "zrh, bsl", earliestDeparture = "2026-12-18", latestReturn = "2027-01-06", minStay = "5", maxStay = "14")

    @Test
    fun validFormHasNoErrors() {
        assertTrue(SearchValidation.validate(valid).isEmpty())
        assertEquals(listOf("ZRH", "BSL"), SearchValidation.parseCodes(valid.origins))
    }

    @Test
    fun reportsErrors() {
        assertTrue(SearchField.ORIGINS in SearchValidation.validate(valid.copy(origins = "")))
        assertTrue(SearchField.ORIGINS in SearchValidation.validate(valid.copy(origins = "ZURICH")))
        assertTrue(SearchField.DESTINATIONS in SearchValidation.validate(valid.copy(destinations = "ZRH")))
        assertTrue(SearchField.LATEST in SearchValidation.validate(valid.copy(latestReturn = "2026-12-01")))
        assertTrue(SearchField.EARLIEST in SearchValidation.validate(valid.copy(earliestDeparture = "18.12.2026")))
        assertTrue(SearchField.MAX_STAY in SearchValidation.validate(valid.copy(minStay = "10", maxStay = "5")))
        assertTrue(SearchField.MIN_STAY in SearchValidation.validate(valid.copy(minStay = "30", maxStay = "40")))
        assertTrue(SearchField.ADULTS in SearchValidation.validate(valid.copy(adults = "0")))
        assertTrue(SearchField.BUDGET in SearchValidation.validate(valid.copy(maxBudget = "-1")))
        assertEquals(SearchField.ORIGINS, SearchValidation.fieldFromBackend("origins.0"))
    }

    @Test
    fun backendUrlMustBeHttps() {
        assertTrue(isAllowedBackendUrl("https://api.example.org"))
        assertTrue(isAllowedBackendUrl("http://10.0.2.2:8000"))
        assertFalse(isAllowedBackendUrl("http://api.example.org"))
        assertFalse(isAllowedBackendUrl("kein url"))
    }
}
