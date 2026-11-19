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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * - 输入去抖 [DEBOUNCE_MS]；旧 query 的在途搜索由 collectLatest 切换时自动取消
 *   （引擎在 channelFlow 内启动全部单服务器搜索，消费取消 = 全部取消）。
 * - 目标服务器每次搜索现取：ServerStore 列表 ∩ 具备 SEARCH 能力的 Handle
 *   （能力判断唯一来源 ProviderHandle.search 非空，禁止按 ServerType 硬编码）。
 * - partial success 由引擎保证：部分服务器失败不影响已到结果的展示。
 * - 分页：loadMore 使用本轮 query 的 targets 续拉下一页，结果回填同一 state。
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
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
    val state: StateFlow<GlobalSearchState> = _state.asStateFlow()

    private var activeTargets: List<SearchTarget> = emptyList()
    private var loadMoreJob: Job? = null

    init {
        viewModelScope.launch {
            query
                .debounce(DEBOUNCE_MS)
                .collectLatest { query ->
                    loadMoreJob?.cancel()
                    if (query.isBlank()) {
                        activeTargets = emptyList()
                        _state.value = GlobalSearchState.idle(query)
                        return@collectLatest
                    }
                    val targets = buildTargets()
                    activeTargets = targets
                    _state.value = GlobalSearchState(
                        query = query,
                        searchingServers = targets.map { it.serverId }.toSet(),
                    )
                    engine.search(targets, query).collect { _state.value = it }
                }
        }
    }

    /** UI 提交查询（原始输入，含空白）；去抖在下游统一做。 */
    fun onQueryChange(query: String) {
        savedStateHandle[SEARCH_QUERY_KEY] = query
    }

    /** 加载下一页；同一时间只允许一个续拉任务。 */
    fun loadMore() {
        val current = _state.value
        if (!current.canLoadMore) return
        val targets = activeTargets
        if (targets.isEmpty()) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            engine.loadMore(targets, current).collect { _state.value = it }
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
    }
}
