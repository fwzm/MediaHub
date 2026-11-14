package com.mediahub.feature.detail.match

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaItem
import com.mediahub.model.PageRequest
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** 手动匹配页状态（条目 129）：四态互斥，每态高度由页面固定，不随结果条数变化。 */
sealed interface ManualMatchState {
    data object Idle : ManualMatchState
    data object Loading : ManualMatchState
    data class Results(val query: String, val candidates: List<MediaItem>) : ManualMatchState
    data class Error(val message: String) : ManualMatchState
}

/**
 * 手动匹配（条目 129 / 133）：独立 ViewModel，复用数据源已有的搜索能力。
 *
 * 刻意不改 `DetailViewModel`：另一路（feature/detail-manual-match-20261114）
 * 正在改详情页的匹配入口与持久化，这里只在独立页面内完成「搜索 → 选择」。
 */
@HiltViewModel
class ManualMatchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val registry: MediaProviderRegistry,
    private val logger: Logger,
) : ViewModel() {

    val serverId: String = checkNotNull(savedStateHandle["serverId"])
    val itemId: String = NavArgCodec.decode(checkNotNull(savedStateHandle["itemId"]))

    private val queryState = MutableStateFlow(savedStateHandle.get<String>(QUERY_KEY).orEmpty())
    val query: StateFlow<String> = queryState.asStateFlow()

    /** 「重试」用：同一 query 重发一次，不依赖输入框变化。 */
    private val retryTick = MutableStateFlow(0)

    fun onQueryChange(value: String) {
        queryState.value = value
    }

    fun retry() {
        retryTick.value += 1
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val state: StateFlow<ManualMatchState> = combine(queryState, retryTick) { query, _ -> query }
        .debounce(MATCH_QUERY_DEBOUNCE_MS)
        .flatMapLatest { raw ->
            flow {
                val keyword = raw.trim()
                if (keyword.isEmpty()) {
                    emit(ManualMatchState.Idle)
                    return@flow
                }
                emit(ManualMatchState.Loading)
                emit(search(keyword))
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ManualMatchState.Idle,
        )

    private suspend fun search(keyword: String): ManualMatchState = try {
        val server = serverStore.getServer(serverId)
            ?: throw ProviderException.NotFound(serverId, "媒体源")
        val handle = registry.create(server)
            ?: throw ProviderException.NotFound(serverId, "该媒体源")
        val search = handle.search
            ?: return ManualMatchState.Error("该媒体源不支持搜索匹配，请改用带搜索能力的来源")
        val page = search.search(keyword, PageRequest(limit = MANUAL_MATCH_PAGE_SIZE))
        ManualMatchState.Results(query = keyword, candidates = page.items)
    } catch (e: Exception) {
        logger.w(LogTag.UI, "手动匹配搜索失败 server=$serverId", e)
        ManualMatchState.Error(e.message ?: "搜索失败，请稍后重试")
    }

    private companion object {
        const val QUERY_KEY = "matchQuery"
        const val MANUAL_MATCH_PAGE_SIZE = 20
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
