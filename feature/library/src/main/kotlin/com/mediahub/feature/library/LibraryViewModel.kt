package com.mediahub.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.PageRequest
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LibraryUiState {
    data object Loading : LibraryUiState

    /** 顶层媒体库 Views（libraryId == "root"）。 */
    data class Libraries(
        val libraries: List<MediaLibrary>,
        val libraryName: String,
    ) : LibraryUiState

    /** 某媒体库内的条目 / 子级浏览。 */
    data class Content(
        val items: List<MediaItem>,
        val libraryName: String,
        val currentFolder: MediaItem? = null,
        val canGoUp: Boolean = false,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        val loadMoreError: String? = null,
    ) : LibraryUiState

    data class Error(val message: String) : LibraryUiState
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val registry: MediaProviderRegistry,
    private val logger: Logger,
) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    private val libraryId: String = checkNotNull(savedStateHandle["libraryId"])
    private val libraryName: String = savedStateHandle["name"] ?: ""

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var currentFolder: MediaItem? = null

    /** 文件夹导航栈（上级回溯）。 */
    private val folderStack = ArrayDeque<MediaItem?>()

    /** 分页：下一页 offset，null 表示没有更多页。 */
    private var nextOffset: Int? = null

    /** loadMore 并发锁。 */
    private var loadingMore = false

    /** 导航代数：每次 openFolder/goToParent/load 递增，用于 race guard。 */
    private var navigationGeneration = 0

    /** loadMore 协程 job，用于取消在途旧请求。 */
    private var loadMoreJob: Job? = null

    init {
        load()
    }

    fun openFolder(folder: MediaItem) {
        loadMoreJob?.cancel()
        navigationGeneration++
        folderStack.addLast(currentFolder)
        currentFolder = folder
        nextOffset = null
        loadingMore = false
        load()
    }

    fun goToParent() {
        loadMoreJob?.cancel()
        navigationGeneration++
        currentFolder = folderStack.removeLastOrNull()
        nextOffset = null
        loadingMore = false
        load()
    }

    fun loadMore() {
        if (loadingMore || nextOffset == null) return
        val currentState = _uiState.value as? LibraryUiState.Content ?: return
        loadingMore = true
        val snapshotGen = navigationGeneration
        val snapshotParent = currentFolder?.id ?: libraryId
        val snapshotOffset = nextOffset!!
        _uiState.value = currentState.copy(isLoadingMore = true, loadMoreError = null)
        loadMoreJob = viewModelScope.launch {
            try {
                val server = serverStore.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val handle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                val page = PageRequest(offset = snapshotOffset, limit = 200)
                val result = handle.library?.getItems(snapshotParent, page)
                    ?: handle.browse?.listFolder(currentFolder, page)
                    ?: throw ProviderException.NotYetImplemented(serverId, "浏览能力")
                // Race guard: 导航已变，丢弃
                if (snapshotGen != navigationGeneration) return@launch
                val currentParent = currentFolder?.id ?: libraryId
                if (currentParent != snapshotParent) return@launch
                // 追加并去重（按 id）
                val state = _uiState.value as? LibraryUiState.Content ?: return@launch
                val existingIds = (state.items.map { it.id }).toSet()
                val newItems = result.items.filter { it.id !in existingIds }
                nextOffset = result.nextOffset
                _uiState.value = state.copy(
                    items = state.items + newItems,
                    hasMore = result.hasMore,
                    isLoadingMore = false,
                    loadMoreError = null,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (snapshotGen != navigationGeneration) return@launch
                val state = _uiState.value as? LibraryUiState.Content ?: return@launch
                _uiState.value = state.copy(
                    isLoadingMore = false,
                    loadMoreError = userMessage(e),
                )
            } finally {
                if (snapshotGen == navigationGeneration) loadingMore = false
            }
        }
    }

    fun load() {
        loadMoreJob?.cancel()
        navigationGeneration++
        viewModelScope.launch {
            _uiState.value = LibraryUiState.Loading
            try {
                val server = serverStore.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val handle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                val page = PageRequest(limit = 200)
                // 能力组合（ADR-014）：媒体库型走 library，文件树型走 browse，均无则提示未接入。
                val library = handle.library
                val browse = handle.browse
                when {
                    // 顶层：显示媒体库 Views（评审 #7，不把 View 伪造成 MediaItem）
                    library != null && libraryId == "root" -> {
                        val libraries = library.getLibraries()
                        _uiState.value = LibraryUiState.Libraries(
                            libraries = libraries,
                            libraryName = libraryName.ifBlank { server.displayName },
                        )
                    }

                    library != null -> {
                        val parentId = currentFolder?.id ?: libraryId
                        val result = library.getItems(parentId, page)
                        nextOffset = result.nextOffset
                        _uiState.value = LibraryUiState.Content(
                            items = result.items,
                            libraryName = libraryName.ifBlank { server.displayName },
                            currentFolder = currentFolder,
                            canGoUp = folderStack.isNotEmpty(),
                            hasMore = result.hasMore,
                        )
                    }

                    browse != null -> {
                        val result = browse.listFolder(currentFolder, page)
                        nextOffset = result.nextOffset
                        _uiState.value = LibraryUiState.Content(
                            items = result.items,
                            libraryName = libraryName.ifBlank { server.displayName },
                            currentFolder = currentFolder,
                            canGoUp = folderStack.isNotEmpty(),
                            hasMore = result.hasMore,
                        )
                    }

                    else -> throw ProviderException.NotYetImplemented(serverId, "该数据源的浏览能力尚未接入")
                }
            } catch (e: Exception) {
                logger.w(LogTag.UI, "加载媒体库失败 serverId=$serverId", e)
                _uiState.value = LibraryUiState.Error(userMessage(e))
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ProviderException -> e.message ?: "加载失败"
        else -> "加载失败：${e.message}"
    }
}
