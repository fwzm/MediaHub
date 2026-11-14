package com.mediahub.feature.library.iptv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.MediaSearchProvider
import com.mediahub.provider.api.ProviderException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** IPTV 频道列表页 UI 状态。 */
sealed interface IptvChannelsUiState {
    data object Loading : IptvChannelsUiState

    data class Error(val message: String) : IptvChannelsUiState

    data class Content(
        val groups: List<MediaLibrary>,
        val selectedGroupId: String,
        val query: String,
        val items: List<MediaItem>,
        val favorites: Set<String>,
        val favoritesOnly: Boolean,
        val hasMore: Boolean,
        val isLoadingMore: Boolean,
        val loadMoreError: String?,
        val loadedCount: Int,
    ) : IptvChannelsUiState
}

/**
 * IPTV 频道列表 ViewModel（段2）。
 *
 * 行为：
 * - 首屏拉 `getLibraries()`（Provider 第一项是“全部频道”聚合视图），默认选中第一项；
 * - 分组切换 / 搜索走 Provider 的分页接口，不把整张播放列表塞进 UI；
 * - 收藏是本地 DataStore，只影响展示过滤，不写 Provider；
 * - 搜索有 300ms 防抖；每次 reload 递增 generation，丢弃过期结果（切分组/连续输入不串台）。
 */
@HiltViewModel
class IptvChannelsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val registry: MediaProviderRegistry,
    private val favoritesRepository: IptvFavoritesRepository,
) : ViewModel() {

    val serverName: String = savedStateHandle.get<String>("name").orEmpty()

    private val serverId: String = savedStateHandle.get<String>("serverId").orEmpty()

    private val _uiState = MutableStateFlow<IptvChannelsUiState>(IptvChannelsUiState.Loading)
    val uiState: StateFlow<IptvChannelsUiState> = _uiState.asStateFlow()

    private var libraryProvider: MediaLibraryProvider? = null
    private var searchProvider: MediaSearchProvider? = null
    private var groups: List<MediaLibrary> = emptyList()
    private var rawItems: List<MediaItem> = emptyList()
    private var nextOffset: Int? = null
    private var selectedGroupId: String = ""
    private var query: String = ""
    private var favorites: Set<String> = emptySet()
    private var favoritesOnly: Boolean = false
    private var loadingMore: Boolean = false
    private var loadMoreError: String? = null
    private var generation: Int = 0
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var fillJob: Job? = null
    private var queryJob: Job? = null

    init {
        favoritesRepository.favorites(serverId)
            .onEach { latest ->
                favorites = latest
                publishIfContent()
            }
            .launchIn(viewModelScope)
        reload(initial = true)
    }

    fun retry() {
        reload(initial = _uiState.value !is IptvChannelsUiState.Content)
    }

    fun selectGroup(groupId: String) {
        if (groupId == selectedGroupId) return
        selectedGroupId = groupId
        query = ""
        reload(initial = true)
    }

    fun setQuery(text: String) {
        query = text
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            reload(initial = true)
        }
    }

    fun toggleFavoritesOnly() {
        favoritesOnly = !favoritesOnly
        publishIfContent()
        if (favoritesOnly) {
            // 收藏可能落在未加载的后续页；开启收藏过滤时补齐全部分页，
            // 否则用户会看到“收藏为空”的假象。
            loadRemainingPages()
        } else {
            fillJob?.cancel()
        }
    }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            favoritesRepository.toggle(serverId, item.id)
        }
    }

    fun loadMore() {
        if (fillJob?.isActive == true) return
        val offset = nextOffset ?: return
        if (loadingMore) return
        loadingMore = true
        loadMoreError = null
        publishIfContent()
        val currentGeneration = generation
        loadMoreJob = viewModelScope.launch {
            try {
                val page = fetch(offset)
                if (currentGeneration != generation) return@launch
                rawItems = (rawItems + page.items).distinctBy { it.id }
                nextOffset = page.nextOffset
                publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (currentGeneration == generation) {
                    loadMoreError = messageOf(error)
                    publishIfContent()
                }
            } finally {
                loadingMore = false
                if (currentGeneration == generation) publishIfContent()
            }
        }
    }

    private fun loadRemainingPages() {
        val firstOffset = nextOffset ?: return
        fillJob?.cancel()
        val currentGeneration = generation
        loadingMore = true
        loadMoreError = null
        fillJob = viewModelScope.launch {
            var offset: Int? = firstOffset
            var pages = 0
            try {
                while (offset != null && currentGeneration == generation && pages < MAX_FILL_PAGES) {
                    val page = fetch(offset)
                    if (currentGeneration != generation) return@launch
                    rawItems = (rawItems + page.items).distinctBy { it.id }
                    nextOffset = page.nextOffset
                    offset = page.nextOffset
                    pages++
                    publish()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (currentGeneration == generation) loadMoreError = messageOf(error)
            } finally {
                loadingMore = false
                if (currentGeneration == generation) publishIfContent()
            }
        }
    }

    private fun reload(initial: Boolean) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        fillJob?.cancel()
        generation++
        val currentGeneration = generation
        loadingMore = false
        loadMoreError = null
        if (initial) _uiState.value = IptvChannelsUiState.Loading

        loadJob = viewModelScope.launch {
            try {
                val server = serverStore.getServer(serverId)
                    ?: throw IllegalStateException("服务器不存在")
                val handle = registry.create(server)
                    ?: throw IllegalStateException("当前数据源不支持浏览")
                val library = handle.library
                    ?: throw IllegalStateException("当前数据源不支持频道列表")
                libraryProvider = library
                searchProvider = handle.search
                groups = library.getLibraries()
                if (groups.none { it.id == selectedGroupId }) {
                    selectedGroupId = groups.firstOrNull()?.id.orEmpty()
                }
                val page = fetch(0)
                if (currentGeneration != generation) return@launch
                rawItems = page.items
                nextOffset = page.nextOffset
                publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (currentGeneration == generation) {
                    _uiState.value = IptvChannelsUiState.Error(messageOf(error))
                }
            }
        }
    }

    private suspend fun fetch(offset: Int): PagedResult<MediaItem> {
        val page = PageRequest(offset = offset, limit = PAGE_SIZE)
        if (query.isNotBlank()) {
            val search = searchProvider
                ?: throw IllegalStateException("当前数据源不支持搜索")
            return search.search(query, page)
        }
        val library = libraryProvider ?: throw IllegalStateException("频道列表尚未加载")
        return library.getItems(selectedGroupId, page)
    }

    private fun publish() {
        val visible = if (favoritesOnly) rawItems.filter { it.id in favorites } else rawItems
        _uiState.value = IptvChannelsUiState.Content(
            groups = groups,
            selectedGroupId = selectedGroupId,
            query = query,
            items = visible,
            favorites = favorites,
            favoritesOnly = favoritesOnly,
            hasMore = nextOffset != null,
            isLoadingMore = loadingMore,
            loadMoreError = loadMoreError,
            loadedCount = rawItems.size,
        )
    }

    private fun publishIfContent() {
        if (_uiState.value is IptvChannelsUiState.Content) publish()
    }

    private fun messageOf(error: Throwable): String = when (error) {
        is ProviderException -> error.message ?: "加载频道失败"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "加载频道失败"
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val SEARCH_DEBOUNCE_MS = 300L

        /** 收藏补齐的硬上限，避免异常源无限分页。 */
        const val MAX_FILL_PAGES = 100
    }
}
