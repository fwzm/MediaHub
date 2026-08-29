package com.mediahub.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaFilter
import com.mediahub.model.MediaFilterField
import com.mediahub.model.MediaListQuery
import com.mediahub.model.MediaSort
import com.mediahub.model.MediaQueryCapabilities
import com.mediahub.model.MediaSortField
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

/** 排序菜单展示顺序（用户口径）；VM 按 Provider 能力过滤后下发。 */
internal val SORT_MENU_ORDER: List<MediaSortField> = listOf(
    MediaSortField.SERVER_DEFAULT,
    MediaSortField.DATE_ADDED,
    MediaSortField.TITLE,
    MediaSortField.COMMUNITY_RATING,
    MediaSortField.CRITIC_RATING,
    MediaSortField.PRODUCTION_YEAR,
    MediaSortField.PREMIERE_DATE,
    MediaSortField.OFFICIAL_RATING,
    MediaSortField.RUNTIME,
    MediaSortField.BITRATE,
    MediaSortField.SIZE,
    MediaSortField.RANDOM,
)

/** 筛选菜单展示顺序（用户口径）；VM 按能力过滤后下发。 */
internal val FILTER_MENU_ORDER: List<MediaFilterField> = listOf(
    MediaFilterField.MEDIA_TYPE,
    MediaFilterField.YEAR,
    MediaFilterField.PLAYED,
    MediaFilterField.FAVORITE,
)

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
        /** 当前排序（1C-2）。 */
        val sort: MediaSort = MediaSort(MediaSortField.SERVER_DEFAULT),
        /** 数据源支持的排序选项（能力过滤后，菜单顺序）；空 = 该源不支持排序入口。 */
        val sortFields: List<MediaSortField> = emptyList(),
        /** 当前筛选（1D，container-scoped：进子级重置、回父级恢复）。 */
        val filter: MediaFilter = MediaFilter(),
        /** 数据源支持的筛选选项（能力过滤后，菜单顺序）；空 = 该源不支持筛选入口。 */
        val filterFields: List<MediaFilterField> = emptyList(),
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

    /**
     * 导航帧（Phase 1D）：容器 + 该容器的筛选状态。
     *
     * Filter 是「当前 container listing 的状态」，**不跨容器继承**（与 Sort 刻意不同）：
     * 进入子容器 push 当前帧并重置子容器筛选；返回父容器 pop 并恢复父容器筛选。
     * 栈式保存保证任意层级往返都能精确还原。
     */
    private data class NavigationFrame(
        val folder: MediaItem?,
        val filter: MediaFilter,
    )

    /** 文件夹导航栈（上级回溯 + 每层筛选恢复）。 */
    private val folderStack = ArrayDeque<NavigationFrame>()

    /** 分页：下一页 offset，null 表示没有更多页。 */
    private var nextOffset: Int? = null

    /** loadMore 并发锁。 */
    private var loadingMore = false

    /** 当前排序（1C-2）：SERVER_DEFAULT 起步，用户选择后才变；不擅自改默认序。 */
    private var currentSort: MediaSort = MediaSort(MediaSortField.SERVER_DEFAULT)

    /** 当前筛选（1D）：默认不过滤；**容器作用域**——进子级重置、回父级恢复。 */
    private var currentFilter: MediaFilter = MediaFilter()

    /** 用户 query 变更尚未成功时保留上一份可用选择；刷新/重试取消旧 job 也不能丢失。 */
    private var sortRollbackOnFailure: MediaSort? = null
    private var filterRollbackOnFailure: MediaFilter? = null

    /** 导航代数：每次 openFolder/goToParent/load 递增，用于 race guard。 */
    private var navigationGeneration = 0

    /** loadMore 协程 job，用于取消在途旧请求。 */
    private var loadMoreJob: Job? = null

    /** 普通 load 协程 job，用于取消在途旧请求。 */
    private var loadJob: Job? = null

    init {
        load()
    }

    fun openFolder(folder: MediaItem) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        navigationGeneration++
        sortRollbackOnFailure = null
        filterRollbackOnFailure = null
        // Filter 容器作用域：push 父帧（含父筛选），子容器筛选重置为不过滤
        folderStack.addLast(NavigationFrame(currentFolder, currentFilter))
        currentFolder = folder
        currentFilter = MediaFilter()
        nextOffset = null
        loadingMore = false
        load()
    }

    fun goToParent() {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        navigationGeneration++
        sortRollbackOnFailure = null
        filterRollbackOnFailure = null
        // Filter 容器作用域：pop 恢复父帧的筛选状态
        val frame = folderStack.removeLastOrNull()
        currentFolder = frame?.folder
        currentFilter = frame?.filter ?: MediaFilter()
        nextOffset = null
        loadingMore = false
        load()
    }

    fun loadMore() {
        val currentState = _uiState.value as? LibraryUiState.Content ?: return
        if (loadingMore || nextOffset == null) return
        loadingMore = true
        val snapshotGen = navigationGeneration
        val snapshotParent = currentFolder?.id ?: libraryId
        val snapshotFolder = currentFolder
        val snapshotOffset = nextOffset!!
        val snapshotSort = currentSort
        val snapshotFilter = currentFilter
        _uiState.value = currentState.copy(isLoadingMore = true, loadMoreError = null)
        loadMoreJob = viewModelScope.launch {
            try {
                val server = serverStore.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val handle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                val page = PageRequest(offset = snapshotOffset, limit = 200)
                val queryProvider = handle.query
                val result = if (queryProvider != null) {
                    // 排序与筛选都下沉服务器；续页沿用同一快照（RANDOM 除外，见 Provider 快照语义）
                    queryProvider.getItems(
                        snapshotParent,
                        MediaListQuery(page = page, sort = snapshotSort, filter = snapshotFilter),
                    )
                } else {
                    handle.library?.getItems(snapshotParent, page)
                        ?: handle.browse?.listFolder(snapshotFolder, page)
                        ?: throw ProviderException.NotYetImplemented(serverId, "浏览能力")
                }
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

    /**
     * 用户改排序（1C-2）：取消在途 load/loadMore → 重置分页 → offset=0
     * 用新 sort 重新请求服务器（load() 本身已含全部这些语义）。
     * 禁止对已加载的当前页做本地 sortedBy——全库排序语义必须是服务端的。
     */
    fun onSortSelected(sort: MediaSort) {
        if (sort.field == currentSort.field && sort.direction == currentSort.direction) return
        if (sortRollbackOnFailure == null) sortRollbackOnFailure = currentSort
        currentSort = sort
        load()
    }

    /**
     * 用户改筛选（1D）：与 onSortSelected 同构。
     *
     * 语义（冻结规格 B 修订）：filter 是「当前 container listing 的状态」，
     * **不跨容器继承**——openFolder 进入子容器时重置为默认、goToParent 返回时
     * 从导航帧恢复父容器筛选；onFilterSelected 只作用于当前容器。
     * 改筛选同样全量重拉（取消在途 → generation++ → offset=0）。
     */
    fun onFilterSelected(filter: MediaFilter) {
        if (filter == currentFilter) return
        if (filterRollbackOnFailure == null) filterRollbackOnFailure = currentFilter
        currentFilter = filter
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        navigationGeneration++
        loadingMore = false
        nextOffset = null
        val snapshotGen = navigationGeneration
        val snapshotParent = currentFolder?.id ?: libraryId
        val snapshotFolder = currentFolder
        val snapshotSort = currentSort
        val snapshotFilter = currentFilter
        loadJob = viewModelScope.launch {
            _uiState.value = LibraryUiState.Loading
            try {
                val server = serverStore.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val handle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                val page = PageRequest(limit = 200)
                val library = handle.library
                val browse = handle.browse
                when {
                    library != null && libraryId == "root" -> {
                        val libraries = library.getLibraries()
                        _uiState.value = LibraryUiState.Libraries(
                            libraries = libraries,
                            libraryName = libraryName.ifBlank { server.displayName },
                        )
                    }

                    library != null -> {
                        val queryProvider = handle.query
                        val result = if (queryProvider != null) {
                            // 排序与筛选都下沉服务器（分页前执行）；无 Query 能力回退旧接口
                            queryProvider.getItems(
                                snapshotParent,
                                MediaListQuery(page = page, sort = snapshotSort, filter = snapshotFilter),
                            )
                        } else {
                            library.getItems(snapshotParent, page)
                        }
                        // Race guard: 导航已变，丢弃
                        if (snapshotGen != navigationGeneration) return@launch
                        val currentParent = currentFolder?.id ?: libraryId
                        if (currentParent != snapshotParent) return@launch
                        nextOffset = result.nextOffset
                        // 能力自述：支持排序/筛选的源给菜单（按能力过滤），否则隐藏入口
                        val caps: MediaQueryCapabilities? = queryProvider?.capabilities
                        _uiState.value = LibraryUiState.Content(
                            items = result.items,
                            libraryName = libraryName.ifBlank { server.displayName },
                            currentFolder = currentFolder,
                            canGoUp = folderStack.isNotEmpty(),
                            hasMore = result.hasMore,
                            sort = snapshotSort,
                            sortFields = caps?.filterSortFields(SORT_MENU_ORDER).orEmpty(),
                            filter = snapshotFilter,
                            filterFields = caps?.filterFilterFields(FILTER_MENU_ORDER).orEmpty(),
                        )
                    }

                    browse != null -> {
                        val result = browse.listFolder(snapshotFolder, page)
                        if (snapshotGen != navigationGeneration) return@launch
                        val currentParent = currentFolder?.id ?: libraryId
                        if (currentParent != snapshotParent) return@launch
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
                if (snapshotGen == navigationGeneration) {
                    sortRollbackOnFailure = null
                    filterRollbackOnFailure = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (snapshotGen != navigationGeneration) return@launch
                sortRollbackOnFailure?.let { currentSort = it }
                filterRollbackOnFailure?.let { currentFilter = it }
                sortRollbackOnFailure = null
                filterRollbackOnFailure = null
                logger.w(LogTag.UI, "加载媒体库失败 serverId=$serverId", e)
                _uiState.value = LibraryUiState.Error(userMessage(e))
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ProviderException -> e.message ?: "加载失败"
        // Provider contract 之外的异常文本可能含 URL/query/credential，禁止直接回显。
        else -> "加载失败"
    }
}
