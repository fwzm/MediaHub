package com.mediahub.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.*
import com.mediahub.provider.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryMixedCapabilitiesTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val gates = mutableListOf<CompletableDeferred<PagedResult<MediaItem>>>()
    private val viewGates = mutableListOf<CompletableDeferred<List<MediaLibrary>>>()
    private var modelCount = 0
    private val server = MediaServer(id = "fixture-source", name = "Fixture", type = ServerType.EMBY,
        baseUrl = "https://fixture.invalid", createdAtEpochMs = 0)
    private val folder = item("folder-a", MediaType.FOLDER)
    private fun item(id: String, type: MediaType = MediaType.VIDEO) = MediaItem("fixture-source", id, type, id)
    private val logger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() {
        gates.forEach { it.complete(PagedResult(emptyList())) }
        viewGates.forEach { it.complete(emptyList()) }
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private inner class Listing : MediaLibraryProvider, MediaBrowseProvider, MediaQueryLibraryProvider {
        var views = 0
        val libraryCalls = mutableListOf<Pair<String, Int>>()
        val browseCalls = mutableListOf<Pair<String?, Int>>()
        val queryCalls = mutableListOf<Pair<String, MediaListQuery>>()
        var heldBrowsePage: CompletableDeferred<PagedResult<MediaItem>>? = null
        var heldViews: CompletableDeferred<List<MediaLibrary>>? = null
        var failFolder = false
        override val capabilities = MediaQueryCapabilities(
            sortFields = setOf(MediaSortField.SERVER_DEFAULT, MediaSortField.TITLE),
            filterFields = setOf(MediaFilterField.YEAR),
        )
        private fun resultPage(tag: String, request: PageRequest): PagedResult<MediaItem> =
            if (request.offset == 0) PagedResult(listOf(item("$tag-1"), item("$tag-2")),
                hasMore = true, nextOffset = 200)
            else PagedResult(listOf(item("$tag-2"), item("$tag-3")), hasMore = false)
        override suspend fun getLibraries(): List<MediaLibrary> {
            views++
            heldViews?.let { held ->
                heldViews = null
                return withContext(NonCancellable) { held.await() }
            }
            return listOf(MediaLibrary(server.id, "view-a", "Fixture library", LibraryType.MOVIES))
        }
        override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
            libraryCalls += libraryId to page.offset
            return resultPage("library-$libraryId", page)
        }
        override suspend fun getItems(libraryId: String, query: MediaListQuery): PagedResult<MediaItem> {
            queryCalls += libraryId to query
            return resultPage("query-$libraryId", query.page)
        }
        override suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> {
            browseCalls += folder?.id to page.offset
            if (failFolder && folder != null) throw ProviderException.Connection(server.id, "fixture folder rejected")
            if (folder != null && page.offset != 0 && heldBrowsePage != null) {
                return withContext(NonCancellable) { heldBrowsePage!!.await() }
            }
            return resultPage("browse-${folder?.id ?: "root"}", page)
        }
        override suspend fun getSeasons(seriesId: String) = emptyList<Season>()
        override suspend fun getEpisodes(seasonId: String) = emptyList<Episode>()
    }

    private fun model(listing: Listing, root: String = "view-a", library: Boolean = true,
                      browse: Boolean = true, query: Boolean = false): LibraryViewModel {
        val provider = object : MediaProvider {
            override val serverId = server.id
            override val type = server.type
            override val displayName = server.name
            override val descriptor = ProviderDescriptor(
                id = "fixture-mixed", serverType = server.type, displayName = server.name,
                category = ProviderCategory.MEDIA_SERVER,
                declaredCapabilities = setOf(ProviderCapability.LIBRARY, ProviderCapability.BROWSE, ProviderCapability.QUERY),
                authMethod = AuthMethod.NONE, status = ProviderStatus.EXPERIMENTAL,
            )
            override suspend fun testConnection() = ConnectionStatus(true)
        }
        val handle = ProviderHandle(provider = provider, library = listing.takeIf { library },
            browse = listing.takeIf { browse }, query = listing.takeIf { query })
        val registry = object : MediaProviderRegistry {
            override val supportedTypes = setOf(server.type)
            override fun descriptors() = listOf(provider.descriptor)
            override fun factoryFor(type: ServerType): MediaProviderFactory? = null
            override fun create(server: MediaServer) = handle
        }
        val servers = object : ServerStore {
            override fun observeServers() = flowOf(listOf(server))
            override suspend fun getServer(id: String) = server.takeIf { it.id == id }
        }
        return LibraryViewModel(SavedStateHandle(mapOf("serverId" to server.id, "libraryId" to root)),
            servers, registry, logger).also { store.put("fixture-${modelCount++}", it) }
    }

    private fun content(vm: LibraryViewModel): LibraryUiState.Content {
        assertTrue("Expected content, got ${vm.uiState.value}", vm.uiState.value is LibraryUiState.Content)
        return vm.uiState.value as LibraryUiState.Content
    }

    @Test fun rootViewsYieldToBrowseOnlyAfterExplicitFolderNavigation() = runTest(dispatcher) {
        val listing = Listing(); val vm = model(listing, root = "root")
        advanceUntilIdle()
        assertTrue(vm.uiState.value is LibraryUiState.Libraries)
        assertEquals(1, listing.views); assertTrue(listing.browseCalls.isEmpty())
        vm.openFolder(folder); advanceUntilIdle()
        assertEquals("browse-folder-a-1", content(vm).items.first().id)
        assertEquals(listOf("folder-a" to 0), listing.browseCalls)
        vm.goToParent(); advanceUntilIdle()
        assertTrue(vm.uiState.value is LibraryUiState.Libraries)
        assertEquals(2, listing.views)
    }

    @Test fun aMixedProviderUsesBrowseForFolderAndItsNextPage() = runTest(dispatcher) {
        val listing = Listing(); val vm = model(listing)
        advanceUntilIdle()
        assertEquals(listOf("view-a" to 0), listing.libraryCalls)
        vm.openFolder(folder); advanceUntilIdle()
        assertEquals("browse-folder-a-1", content(vm).items.first().id)
        vm.loadMore(); advanceUntilIdle()
        assertEquals(listOf("folder-a" to 0, "folder-a" to 200), listing.browseCalls)
        assertEquals(listOf("view-a" to 0), listing.libraryCalls)
        assertEquals(listOf("browse-folder-a-1", "browse-folder-a-2", "browse-folder-a-3"), content(vm).items.map { it.id })
        assertFalse(content(vm).hasMore)
    }

    @Test fun queryMenusBelongToLibraryListingsAndReturnWithParentFilter() = runTest(dispatcher) {
        val listing = Listing(); val vm = model(listing, query = true)
        advanceUntilIdle()
        vm.onFilterSelected(MediaFilter(year = 2024)); advanceUntilIdle()
        assertTrue(content(vm).filterFields.isNotEmpty())
        assertEquals(2024, listing.queryCalls.last().second.filter.year)
        vm.openFolder(folder); advanceUntilIdle()
        assertEquals("browse-folder-a-1", content(vm).items.first().id)
        assertTrue(content(vm).filterFields.isEmpty()); assertTrue(content(vm).sortFields.isEmpty())
        vm.loadMore(); advanceUntilIdle()
        assertEquals(2, listing.queryCalls.size)
        assertEquals(listOf("folder-a" to 0, "folder-a" to 200), listing.browseCalls)
        vm.goToParent(); advanceUntilIdle()
        assertEquals(2024, content(vm).filter.year)
        assertEquals("view-a", listing.queryCalls.last().first)
        assertEquals(2024, listing.queryCalls.last().second.filter.year)
    }

    @Test fun libraryOnlyFoldersKeepTheirLibraryRoute() = runTest(dispatcher) {
        val listing = Listing(); val vm = model(listing, browse = false)
        advanceUntilIdle(); vm.openFolder(folder); advanceUntilIdle(); vm.loadMore(); advanceUntilIdle()
        assertEquals(listOf("view-a" to 0, "folder-a" to 0, "folder-a" to 200), listing.libraryCalls)
        assertEquals("library-folder-a-1", content(vm).items.first().id)
    }

    @Test fun browseOnlyRootAndPaginationUseTheSameFolderContext() = runTest(dispatcher) {
        val listing = Listing(); val vm = model(listing, root = "root", library = false)
        advanceUntilIdle(); vm.loadMore(); advanceUntilIdle()
        assertEquals(listOf(null to 0, null to 200), listing.browseCalls)
        assertEquals(3, content(vm).items.size); assertTrue(listing.libraryCalls.isEmpty())
    }

    @Test fun staleBrowsePaginationCannotReplaceTheReturnedParent() = runTest(dispatcher) {
        val listing = Listing(); val vm = model(listing)
        advanceUntilIdle(); vm.openFolder(folder); advanceUntilIdle()
        assertEquals("browse-folder-a-1", content(vm).items.first().id)
        val gate = CompletableDeferred<PagedResult<MediaItem>>(); gates += gate; listing.heldBrowsePage = gate
        vm.loadMore(); runCurrent()
        assertTrue(content(vm).isLoadingMore)
        vm.goToParent(); runCurrent()
        assertEquals("library-view-a-1", content(vm).items.first().id)
        gate.complete(PagedResult(listOf(item("fixture-stale-child"))))
        advanceUntilIdle()
        assertEquals("library-view-a-1", content(vm).items.first().id)
        assertFalse(content(vm).items.any { it.id == "fixture-stale-child" })
        assertFalse(content(vm).isLoadingMore)
    }

    @Test fun queryWithoutBrowseKeepsFolderSortFilterAndPagination() = runTest(dispatcher) {
        val listing = Listing(); val vm = model(listing, browse = false, query = true)
        advanceUntilIdle(); vm.openFolder(folder); advanceUntilIdle()
        val sort = MediaSort(MediaSortField.TITLE)
        vm.onSortSelected(sort); advanceUntilIdle()
        vm.onFilterSelected(MediaFilter(year = 2023)); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        assertEquals("folder-a", listing.queryCalls.last().first)
        assertEquals(200, listing.queryCalls.last().second.page.offset)
        assertEquals(sort, listing.queryCalls.last().second.sort)
        assertEquals(2023, listing.queryCalls.last().second.filter.year)
        assertTrue(listing.browseCalls.isEmpty()); assertTrue(listing.libraryCalls.isEmpty())
    }

    @Test fun aBrowseFailureIsNotRetriedAgainstTheLibraryInterface() = runTest(dispatcher) {
        val listing = Listing(); val vm = model(listing)
        advanceUntilIdle(); listing.failFolder = true
        vm.openFolder(folder); advanceUntilIdle()
        assertTrue(vm.uiState.value is LibraryUiState.Error)
        assertEquals(listOf("view-a" to 0), listing.libraryCalls)
        assertEquals(listOf("folder-a" to 0), listing.browseCalls)
    }

    @Test fun browseWithQueryButWithoutLibraryDoesNotSwitchApisOnNextPage() = runTest(dispatcher) {
        val listing = Listing(); val vm = model(listing, root = "root", library = false, query = true)
        advanceUntilIdle(); vm.loadMore(); advanceUntilIdle()
        assertEquals(listOf(null to 0, null to 200), listing.browseCalls)
        assertTrue(listing.queryCalls.isEmpty())
        assertEquals(3, content(vm).items.size)
    }

    @Test fun lateRootViewsCannotReplaceAnExplicitFolderListing() = runTest(dispatcher) {
        val listing = Listing()
        val gate = CompletableDeferred<List<MediaLibrary>>(); viewGates += gate; listing.heldViews = gate
        val vm = model(listing, root = "root")
        runCurrent()
        assertEquals(1, listing.views)
        vm.openFolder(folder); runCurrent()
        assertEquals("browse-folder-a-1", content(vm).items.first().id)
        gate.complete(listOf(MediaLibrary(server.id, "fixture-late-root", "Old root", LibraryType.MOVIES)))
        advanceUntilIdle()
        assertEquals("browse-folder-a-1", content(vm).items.first().id)
        assertEquals(folder.id, content(vm).currentFolder?.id)
    }

    @Test fun aLibraryOnlyRootAlsoUsesItemsAfterExplicitFolderNavigation() = runTest(dispatcher) {
        val listing = Listing(); val vm = model(listing, root = "root", browse = false)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is LibraryUiState.Libraries)
        vm.openFolder(folder); advanceUntilIdle()
        assertEquals("library-folder-a-1", content(vm).items.first().id)
        assertEquals(listOf("folder-a" to 0), listing.libraryCalls)
        assertEquals(1, listing.views)
    }
}
