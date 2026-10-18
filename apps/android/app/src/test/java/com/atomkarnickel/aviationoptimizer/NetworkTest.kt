package com.atomkarnickel.aviationoptimizer

import com.atomkarnickel.aviationoptimizer.data.ApiResult
import com.atomkarnickel.aviationoptimizer.data.AppError
import com.atomkarnickel.aviationoptimizer.data.AppSettings
import com.atomkarnickel.aviationoptimizer.data.BackendSearchRepository
import com.atomkarnickel.aviationoptimizer.data.GitHubReleaseRepository
import com.atomkarnickel.aviationoptimizer.data.remote.GitHubApi
import com.atomkarnickel.aviationoptimizer.data.remote.SearchRequestDto
import com.atomkarnickel.aviationoptimizer.domain.ReleaseCheck
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class NetworkTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() = server.shutdown()

    private fun backend(url: String = server.url("/").toString().replace("127.0.0.1", "localhost")) =
        BackendSearchRepository(FakeSettings(AppSettings(backendUrl = url)), client, testJson)

    private val request = SearchRequestDto(listOf("ZRH"), listOf("SIN"), "2026-12-18", "2027-01-06", 5, 14, 1, "economy")

    @Test
    fun parsesSearchResponse() = runTest {
        server.enqueue(MockResponse().setBody(testJson.encodeToString(sampleResponse())))
        val r = backend().search(request)
        assertTrue(r is ApiResult.Success)
        assertEquals(3, (r as ApiResult.Success).value.results.size)
        val sent = server.takeRequest()
        assertEquals("/api/v1/search", sent.path)
        assertTrue(sent.body.readUtf8().contains("\"earliest_departure\":\"2026-12-18\""))
    }

    @Test
    fun mapsValidationErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"Ungültige Eingabe","errors":[{"field":"origins.0","message":"Ungültiger Flughafen-Code"}]}"""))
        val r = backend().search(request) as ApiResult.Failure
        assertEquals(AppError.Kind.VALIDATION, r.error.kind)
        assertEquals("Ungültiger Flughafen-Code", r.error.fieldErrors["origins.0"])
    }

    @Test
    fun mapsRateLimitAndServerErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(AppError.Kind.RATE_LIMITED, (backend().meta() as ApiResult.Failure).error.kind)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(AppError.Kind.SERVER, (backend().meta() as ApiResult.Failure).error.kind)
    }

    @Test
    fun networkFailureAndMissingConfig() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val r = backend().meta() as ApiResult.Failure
        assertTrue(r.error.kind == AppError.Kind.NETWORK || r.error.kind == AppError.Kind.TIMEOUT)
        assertEquals(AppError.Kind.NOT_CONFIGURED, (backend("").meta() as ApiResult.Failure).error.kind)
        assertEquals(AppError.Kind.NOT_CONFIGURED, (backend("http://insecure.example.org").meta() as ApiResult.Failure).error.kind)
    }

    private fun github(): GitHubApi = Retrofit.Builder().baseUrl(server.url("/")).client(client)
        .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType())).build().create(GitHubApi::class.java)

    @Test
    fun releaseCheckOkThenStaleCacheWhenOffline() = runTest {
        var now = 0L
        val repo = GitHubReleaseRepository(github(), REPO, "aviation-optimizer-", cacheMillis = 1000, clock = { now })
        server.enqueue(MockResponse().setBody(testJson.encodeToString(listOf(release("v1.0.0"), release("v1.1.0"), release("v1.2.0-beta.1", prerelease = true)))))
        val first = repo.check() as ReleaseCheck.Ok
        assertEquals("1.1.0", first.latest.version)
        assertEquals("/repos/Atomkarnickel/aviation-trip-optimizer/releases?per_page=30", server.takeRequest().path)
        // innerhalb der Cache-Zeit keine neue Anfrage
        assertEquals(first, repo.check())
        assertEquals(1, server.requestCount)
        now = 5000
        server.enqueue(MockResponse().setResponseCode(403))
        val stale = repo.check() as ReleaseCheck.Ok
        assertTrue(stale.stale)
    }

    @Test
    fun releaseCheckErrors() = runTest {
        val repo = GitHubReleaseRepository(github(), REPO, "aviation-optimizer-", cacheMillis = 0, clock = { 0L })
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(repo.check(true) is ReleaseCheck.Unavailable)
        server.enqueue(MockResponse().setBody("[]"))
        assertTrue(repo.check(true) is ReleaseCheck.NoRelease)
        server.enqueue(MockResponse().setBody("{\"message\":\"unerwartet\"}"))
        assertTrue(repo.check(true) is ReleaseCheck.Unavailable)
    }
}
