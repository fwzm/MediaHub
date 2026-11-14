package com.mediahub.feature.library

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.feature.library.favorites.FavoritesUiState
import com.mediahub.feature.library.favorites.FavoritesViewModel
import com.mediahub.model.LibraryType
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.api.MediaFavoriteProvider
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 列表名称排序接入（条目 91）：客户端完整列表复用 TitleSorter。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTitleSortTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    private class FakeLogger : Logger {
        override fun d(tag: LogTag, message: String) {}
        override fun i(tag: LogTag, message: String) {}
        override fun w(tag: LogTag, message: String, throwable: Throwable?) {}
        override fun e(tag: LogTag, message: String, throwable: Throwable?) {}
    }

    private val testServer = MediaServer(
        id = "srv-1",
        name = "Test",
        type = ServerType.EMBY,
        baseUrl = "https://example.com",
        createdAtEpochMs = 0L,
    )

    private fun serverStore(): ServerStore = object : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(listOf(testServer))
        override suspend fun getServer(id: String): MediaServer? = testServer
    }

    private fun registry(
        libraries: List<MediaLibrary>,
        favorites: List<MediaItem>,
    ): MediaProviderRegistry = object : MediaProviderRegistry {
        override fun factoryFor(type: ServerType) = null
        override val supportedTypes = emptySet<ServerType>()
        override fun descriptors() = emptyList<ProviderDescriptor>()
        override fun create(server: MediaServer) = ProviderHandle(
            provider = object : MediaProvider {
                override val serverId = "srv-1"
                override val type = ServerType.EMBY
                override val displayName = "Test"
                override val descriptor = ProviderDescriptor(
                    id = "emby",
                    serverType = ServerType.EMBY,
                    displayName = "Emby",
                    category = ProviderCategory.MEDIA_SERVER,
                    declaredCapabilities = setOf(
                        ProviderCapability.LIBRARY,
                        ProviderCapability.DETAIL,
                        ProviderCapability.FAVORITE,
                    ),
                    authMethod = AuthMethod.USERNAME_PASSWORD,
                    status = ProviderStatus.STABLE,
                )

                override suspend fun testConnection() = ConnectionStatus.Unknown
            },
            library = object : MediaLibraryProvider {
                override suspend fun getLibraries() = libraries
                override suspend fun getItems(libraryId: String, page: PageRequest) =
                    PagedResult(items = emptyList<MediaItem>())

                override suspend fun getSeasons(seriesId: String) = error("legacy")
                override suspend fun getEpisodes(seasonId: String) = error("legacy")
            },
            detail = object : MediaDetailProvider {
                override suspend fun getItemDetail(itemId: String) = error("unused")
            },
            favorite = object : MediaFavoriteProvider {
                override suspend fun setFavorite(itemId: String, favorite: Boolean) = favorite
                override suspend fun isFavorite(itemId: String) = true
                override suspend fun listFavorites(limit: Int) = favorites
            },
        )
    }

    private fun item(id: String, title: String): MediaItem =
        MediaItem(serverId = "srv-1", id = id, type = MediaType.MOVIE, title = title)

    @Test
    fun `library root list is ordered by title semantics`() = runTest(testDispatcher) {
        val libraries = listOf(
            MediaLibrary("srv-1", "lib-2", "变形金刚", LibraryType.MOVIES),
            MediaLibrary("srv-1", "lib-1", "阿凡达", LibraryType.MOVIES),
            MediaLibrary("srv-1", "lib-3", "长津湖", LibraryType.MOVIES),
        )
        val vm = LibraryViewModel(
            SavedStateHandle(mapOf("serverId" to "srv-1", "libraryId" to "root", "name" to "Test")),
            serverStore(),
            registry(libraries, emptyList()),
            FakeLogger(),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is LibraryUiState.Libraries)
        assertEquals(
            listOf("阿凡达", "变形金刚", "长津湖"),
            (state as LibraryUiState.Libraries).libraries.map { it.name },
        )
    }

    @Test
    fun `favorites list is ordered by title semantics`() = runTest(testDispatcher) {
        val favorites = listOf(
            item("c", "长津湖"),
            item("a", "阿凡达"),
            item("b", "变形金刚"),
        )
        val vm = FavoritesViewModel(
            SavedStateHandle(mapOf("serverId" to "srv-1")),
            serverStore(),
            registry(emptyList(), favorites),
            FakeLogger(),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is FavoritesUiState.Content)
        assertEquals(
            listOf("阿凡达", "变形金刚", "长津湖"),
            (state as FavoritesUiState.Content).items.map { it.title },
        )
    }
}
