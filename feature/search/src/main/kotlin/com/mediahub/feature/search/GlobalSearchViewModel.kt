package com.mediahub.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.feature.search.engine.GlobalSearchEngine
import com.mediahub.feature.search.engine.GlobalSearchState
import com.mediahub.feature.search.engine.SearchTarget
import com.mediahub.provider.api.MediaProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal const val SEARCH_QUERY_KEY = "searchQuery"

/**
 * 聚合搜索 ViewModel（Phase 1C-1）。
 *
 * - 输入去抖 [DEBOUNCE_MS]；query 变化时取消上一轮搜索（含续拉）。
 * - 目标服务器每次搜索现取：ServerStore 列表 ∩ 具备 SEARCH 能力的 Handle
 *   （能力判断唯一来源 ProviderHandle.search 非空，禁止按 ServerType 硬编码）。
 * - partial success 由引擎保证：部分服务器失败不影响已到结果的展示。
 * - 分页：引擎逐源给出 nextOffset；[loadMore] 只续拉还有下一页的源，
 *   并把新页与已展示结果按 (serverId, id) 去重合并。
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val registry: MediaProviderRegistry,
    private val engine: GlobalSearchEngine,
    private val logger: Logger,
) : ViewModel() {

    /** 输入框与正式搜索共用一个可恢复的 source of truth，避免进程重建后 UI/VM 分裂。 */
    val query: StateFlow<String> = savedStateHandle.getStateFlow(SEARCH_QUERY_KEY, "")

    private val _state = MutableStateFlow(GlobalSearchState.idle())

    /** 搜索结果状态；首帧为空态，不依赖 UI 订阅才启动搜索。 */
    val state: StateFlow<GlobalSearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var activeQuery: String = ""
    private var activeTargets: List<SearchTarget> = emptyList()

    init {
        viewModelScope.launch {
            query.debounce(DEBOUNCE_MS).collectLatest { startSearch(it) }
        }
    }

    /** UI 提交查询（原始输入，含空白）；去抖在下游统一做。 */
    fun onQueryChange(query: String) {
        savedStateHandle[SEARCH_QUERY_KEY] = query
    }

    /** 续拉下一页：只请求 hasMore=true 且 nextOffset 非空的源。 */
    fun loadMore() {
        val snapshot = _state.value
        if (snapshot.isSearching || !snapshot.hasMore || activeQuery.isBlank()) return

        val offsets = snapshot.nextOffsetByServer.mapNotNull { (serverId, offset) ->
            if (offset != null && snapshot.hasMoreByServer[serverId] == true) serverId to offset else null
        }.toMap()
        if (offsets.isEmpty()) return

        val moreTargets = activeTargets.filter { offsets.containsKey(it.serverId) }
        if (moreTargets.isEmpty()) return

        val base = snapshot
        val moreIds = moreTargets.map { it.serverId }.toSet()
        _state.value = base.copy(
            searchingServers = moreIds,
            completedServers = base.completedServers - moreIds,
        )
        searchJob = viewModelScope.launch {
            engine.search(
                targets = moreTargets,
                query = activeQuery,
                pageSize = PAGE_SIZE,
                offsetsByServer = offsets,
            ).collect { page ->
                _state.value = base.copy(
                    hits = (base.hits + page.hits).distinctBy { it.item.serverId to it.item.id },
                    searchingServers = page.searchingServers,
                    completedServers = (base.completedServers - moreIds) + page.completedServers,
                    errors = base.errors + page.errors,
                    hasMoreByServer = base.hasMoreByServer + page.hasMoreByServer,
                    nextOffsetByServer = base.nextOffsetByServer + page.nextOffsetByServer,
                )
            }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun startSearch(query: String) {
        searchJob?.cancel()
        activeQuery = query
        activeTargets = emptyList()
        if (query.isBlank()) {
            _state.value = GlobalSearchState.idle(query)
            return
        }

        val targets = buildTargets()
        activeTargets = targets
        if (targets.isEmpty()) {
            _state.value = GlobalSearchState(query = query)
            return
        }

        _state.value = GlobalSearchState(
            query = query,
            searchingServers = targets.map { it.serverId }.toSet(),
        )
        searchJob = viewModelScope.launch {
            engine.search(targets = targets, query = query, pageSize = PAGE_SIZE)
                .collect { _state.value = it }
        }
    }

    /** 组装搜索目标；Handle 创建失败按无能力处理，不拖垮其它服务器。 */
    private suspend fun buildTargets(): List<SearchTarget> {
        val servers = serverStore.observeServers().first()
        return servers.mapNotNull { server ->
            val handle = runCatching { registry.create(server) }
                .onFailure { logger.w(LogTag.UI, "创建 ProviderHandle 失败 server=${server.name}", it) }
                .getOrNull()
            val search = handle?.search ?: return@mapNotNull null
            SearchTarget(serverId = server.id, serverName = server.name) { query, page ->
                search.search(query, page)
            }
        }
    }

    private companion object {
        /** 输入去抖：连续击键只触发最后一次（冰 → 冰血 → 冰血暴）。 */
        const val DEBOUNCE_MS = 350L

        /** 单页大小；续拉偏移由每个源自己的 PagedResult.nextOffset 决定。 */
        const val PAGE_SIZE = 30
    }
}
