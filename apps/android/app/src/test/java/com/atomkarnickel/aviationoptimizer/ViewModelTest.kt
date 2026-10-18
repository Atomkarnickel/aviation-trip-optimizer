package com.atomkarnickel.aviationoptimizer

import com.atomkarnickel.aviationoptimizer.data.ApiResult
import com.atomkarnickel.aviationoptimizer.data.AppError
import com.atomkarnickel.aviationoptimizer.data.ReleaseRepository
import com.atomkarnickel.aviationoptimizer.data.local.AircraftPreferenceEntity
import com.atomkarnickel.aviationoptimizer.domain.ReleaseCheck
import com.atomkarnickel.aviationoptimizer.domain.ReleaseRules
import com.atomkarnickel.aviationoptimizer.domain.SearchField
import com.atomkarnickel.aviationoptimizer.domain.UpdateState
import com.atomkarnickel.aviationoptimizer.ui.about.AboutViewModel
import com.atomkarnickel.aviationoptimizer.ui.about.AppInfo
import com.atomkarnickel.aviationoptimizer.ui.discover.BackendState
import com.atomkarnickel.aviationoptimizer.ui.discover.DiscoverViewModel
import com.atomkarnickel.aviationoptimizer.ui.discover.ResultFilter
import com.atomkarnickel.aviationoptimizer.ui.discover.SearchState
import com.atomkarnickel.aviationoptimizer.ui.discover.SortKey
import com.atomkarnickel.aviationoptimizer.ui.discover.visibleResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeSearchRepository()
    private val settings = FakeSettings()
    private val favorites = FakeFavorites().apply {
        aircraftState.value = mapOf("A380" to AircraftPreferenceEntity("A380", "wishlist", null))
    }

    private fun vm() = DiscoverViewModel(repo, settings, favorites, emptyCatalog)

    private fun validForm(vm: DiscoverViewModel) = vm.updateForm {
        it.copy(origins = "ZRH", destinations = "SIN", earliestDeparture = "2026-12-18", latestReturn = "2027-01-06")
    }

    @Test
    fun initialStateUsesSettingsAndWishlist() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("ZRH", s.form.origins)
        assertEquals(setOf("A380"), s.form.desiredAircraft)
        assertTrue(s.backend is BackendState.Connected)
    }

    @Test
    fun invalidInputDoesNotCallBackend() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.updateForm { it.copy(origins = "") }
        vm.search()
        advanceUntilIdle()
        assertTrue(SearchField.ORIGINS in vm.state.value.fieldErrors)
        assertTrue(repo.searchRequests.isEmpty())
    }

    @Test
    fun successfulSearchSendsPreferences() = runTest {
        val vm = vm()
        advanceUntilIdle()
        validForm(vm)
        vm.search()
        advanceUntilIdle()
        val s = vm.state.value.search
        assertTrue(s is SearchState.Success)
        val req = repo.searchRequests.single()
        assertEquals(listOf("ZRH"), req.origins)
        assertEquals(listOf("A380"), req.desiredAircraft)
        assertEquals("wishlist", req.preferences.aircraftPreferences.single().status)
    }

    @Test
    fun duplicateSearchWhileLoadingIsIgnored() = runTest {
        repo.gate = CompletableDeferred()
        val vm = vm()
        advanceUntilIdle()
        validForm(vm)
        vm.search()
        advanceUntilIdle()
        assertEquals(SearchState.Loading, vm.state.value.search)
        vm.search()
        vm.search()
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repo.searchRequests.size)
    }

    @Test
    fun backendValidationErrorsMapToFields() = runTest {
        repo.searchResult = ApiResult.Failure(AppError(AppError.Kind.VALIDATION, "Ungültig", mapOf("origins.0" to "Ungültiger Flughafen-Code")))
        val vm = vm()
        advanceUntilIdle()
        validForm(vm)
        vm.search()
        advanceUntilIdle()
        assertTrue(vm.state.value.search is SearchState.Failed)
        assertEquals("Ungültiger Flughafen-Code", vm.state.value.fieldErrors[SearchField.ORIGINS])
    }

    @Test
    fun offlineBackendIsReported() = runTest {
        repo.metaResult = ApiResult.Failure(AppError(AppError.Kind.NETWORK, "Backend nicht erreichbar."))
        val vm = vm()
        advanceUntilIdle()
        val b = vm.state.value.backend
        assertTrue(b is BackendState.Unavailable)
        assertEquals("Backend nicht erreichbar.", (b as BackendState.Unavailable).message)
    }

    @Test
    fun modeChangeRescoresWithoutNewSearch() = runTest {
        val vm = vm()
        advanceUntilIdle()
        validForm(vm)
        vm.search()
        advanceUntilIdle()
        vm.changeMode("cheapest")
        advanceUntilIdle()
        assertEquals(1, repo.searchRequests.size)
        assertEquals("cheapest", repo.rescorePrefs.single().mode)
        assertEquals("cheapest", settings.state.value.mode)
        assertEquals("cheapest", (vm.state.value.search as SearchState.Success).response.mode)
    }

    @Test
    fun favoritesAndCompareSelection() = runTest {
        val vm = vm()
        advanceUntilIdle()
        validForm(vm)
        vm.search()
        advanceUntilIdle()
        val item = (vm.state.value.search as SearchState.Success).response.results.first()
        vm.toggleFavorite(item)
        advanceUntilIdle()
        assertTrue(item.itinerary.id in vm.state.value.favoriteIds)
        listOf("a", "b", "c", "d", "e").forEach(vm::toggleCompare)
        assertEquals(4, vm.state.value.compareIds.size)
    }

    @Test
    fun resultFilteringAndSorting() {
        val results = sampleResponse().results
        assertEquals(listOf("a", "b", "c"), visibleResults(results, ResultFilter(), SortKey.SCORE).map { it.itinerary.id })
        assertEquals(listOf("b", "a", "c"), visibleResults(results, ResultFilter(), SortKey.PRICE).map { it.itinerary.id })
        assertEquals(listOf("b"), visibleResults(results, ResultFilter(wishlistOnly = true), SortKey.SCORE).map { it.itinerary.id })
        assertEquals(listOf("a", "c"), visibleResults(results, ResultFilter(nonstopOnly = true), SortKey.SCORE).map { it.itinerary.id })
        assertEquals("b", visibleResults(results, ResultFilter(), SortKey.AIRCRAFT).first().itinerary.id)
    }
}

class FakeReleases(var result: ReleaseCheck, var sha: String? = "a".repeat(64)) : ReleaseRepository {
    var calls = 0
    override suspend fun check(force: Boolean): ReleaseCheck {
        calls++
        return result
    }
    override suspend fun checksum(url: String) = sha
}

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun ok(vararg tags: String): ReleaseCheck.Ok {
        val list = ReleaseRules.stableSorted(tags.map { release(it) }, REPO, "aviation-optimizer-")
        return ReleaseCheck.Ok(list.first(), list, 0)
    }

    private fun info(version: String) = AppInfo("Aviation Trip Optimizer", version, 10000, REPO)

    @Test
    fun updateAvailableAndDownloadLink() = runTest {
        val vm = AboutViewModel(FakeReleases(ok("v1.0.0", "v1.1.0")), info("1.0.0"))
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("1.0.0", s.installedVersion)
        assertEquals(UpdateState.UPDATE_AVAILABLE, s.updateState)
        assertEquals("a".repeat(64), s.sha256)
        assertEquals("$DL/v1.1.0/aviation-optimizer-1.1.0.apk", vm.downloadUrl())
        // Ein Download ist keine Installation: Status bleibt "Update verfügbar".
        assertEquals(UpdateState.UPDATE_AVAILABLE, vm.state.value.updateState)
        assertTrue(vm.state.value.downloadNotice!!.contains("bestätige die Installation"))
    }

    @Test
    fun currentVersion() = runTest {
        val vm = AboutViewModel(FakeReleases(ok("v1.0.0")), info("1.0.0"))
        advanceUntilIdle()
        assertEquals(UpdateState.CURRENT, vm.state.value.updateState)
    }

    @Test
    fun offlineKeepsInstalledVersionAndMarksUnknown() = runTest {
        val vm = AboutViewModel(FakeReleases(ReleaseCheck.Unavailable("Netzwerkfehler", 0)), info("1.0.0"))
        advanceUntilIdle()
        assertEquals(UpdateState.UNKNOWN, vm.state.value.updateState)
        assertEquals("1.0.0", vm.state.value.installedVersion)
        assertNull(vm.downloadUrl())
    }

    @Test
    fun unsafeAssetUrlIsNeverOpened() = runTest {
        val bad = ReleaseRules.parseStable(release("v2.0.0"), REPO, "aviation-optimizer-")!!.copy(apkUrl = "https://evil.example/x.apk")
        val vm = AboutViewModel(FakeReleases(ReleaseCheck.Ok(bad, listOf(bad), 0)), info("1.0.0"))
        advanceUntilIdle()
        assertNull(vm.downloadUrl())
        assertNull(vm.state.value.downloadNotice)
    }

    @Test
    fun refreshForcesNewCheck() = runTest {
        val repo = FakeReleases(ok("v1.0.0"))
        val vm = AboutViewModel(repo, info("1.0.0"))
        advanceUntilIdle()
        repo.result = ok("v1.0.0", "v1.0.1")
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, repo.calls)
        assertNotNull(vm.state.value.check)
        assertEquals(UpdateState.UPDATE_AVAILABLE, vm.state.value.updateState)
    }
}
