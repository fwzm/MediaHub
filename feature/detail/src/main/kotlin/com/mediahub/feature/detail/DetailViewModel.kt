package com.mediahub.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.LogTag
import com.mediahub.feature.detail.source.CanonicalSourceResolver
import com.mediahub.feature.detail.source.SourceResolution
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 详情页（Phase 1B-3.1）：server → handle.detail.getItemDetail。
 * 对 SERIES 继续通过 browse 链加载季/集（复用 getItems(parentId)），
 * 不实现专用 getSeasons/getEpisodes。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val registry: MediaProviderRegistry,
    private val sourceResolver: CanonicalSourceResolver,
    private val logger: Logger,
) : ViewModel() {

    val serverId: String = checkNotNull(savedStateHandle["serverId"])
    val itemId: String = NavArgCodec.decode(checkNotNull(savedStateHandle["itemId"]))

    private val _uiState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val _seriesState = MutableStateFlow(SeriesBrowseState())
    val seriesState: StateFlow<SeriesBrowseState> = _seriesState.asStateFlow()

    private val _sourceState = MutableStateFlow<SourceResolutionState>(SourceResolutionState.Idle)
    val sourceState: StateFlow<SourceResolutionState> = _sourceState.asStateFlow()

    private var handle: ProviderHandle? = null
    private var episodeJob: Job? = null
    private var sourceJob: Job? = null

    init { load() }

    fun load() {
        _uiState.value = DetailUiState.Loading
        viewModelScope.launch {
            try {
                val server = serverStore.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val h = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                handle = h
                val detailProvider = h.detail
                    ?: throw ProviderException.NotYetImplemented(serverId, "该数据源的详情能力尚未接入")
                val detail = detailProvider.getItemDetail(itemId)
                _uiState.value = DetailUiState.Content(detail)
                startSourceResolution(detail.item)

                // 仅 SERIES 类型加载季列表（复用 browse 链）
                if (detail.item.type == MediaType.SERIES) {
                    if (h.library != null) {
                        loadSeasons(h.library!!, detail.item.id)
                    } else {
                        _seriesState.value = SeriesBrowseState(libraryUnavailable = true)
                    }
                }
            } catch (e: Exception) {
                logger.w(LogTag.UI, "详情加载失败 serverId=$serverId itemId=$itemId", e)
                _uiState.value = DetailUiState.Error(userMessage(e))
            }
        }
    }

    private fun loadSeasons(library: com.mediahub.provider.api.MediaLibraryProvider, seriesId: String) {
        _seriesState.value = _seriesState.value.copy(seasonsLoading = true)
        viewModelScope.launch {
            try {
                val result = library.getItems(seriesId, PageRequest(limit = 50))
                val seasons = sortSeasons(result.items.filter { it.type == MediaType.SEASON })
                val defaultSeason = seasons.firstOrNull { (it.seasonNumber ?: 0) > 0 }
                    ?: seasons.firstOrNull()
                _seriesState.value = _seriesState.value.copy(
                    seasons = seasons,
                    seasonsLoading = false,
                    selectedSeasonId = defaultSeason?.id,
                )
                defaultSeason?.let { loadEpisodesForSeason(it.id) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _seriesState.value = _seriesState.value.copy(
                    seasonsLoading = false,
                    seasonsError = userMessage(e),
                )
            }
        }
    }

    fun selectSeason(seasonId: String) {
        if (_seriesState.value.selectedSeasonId == seasonId) return
        _seriesState.value = _seriesState.value.copy(selectedSeasonId = seasonId)
        loadEpisodesForSeason(seasonId)
    }

    fun retryEpisodes() {
        _seriesState.value.selectedSeasonId?.let { loadEpisodesForSeason(it) }
    }

    private fun loadEpisodesForSeason(seasonId: String) {
        episodeJob?.cancel()
        val library = handle?.library ?: return
        _seriesState.value = _seriesState.value.copy(episodesLoading = true, episodesError = null)
        episodeJob = viewModelScope.launch {
            val requestedSeasonId = seasonId
            try {
                val result = library.getItems(seasonId, PageRequest(limit = 200))
                // 并发安全：只有当前仍选中的季才更新
                if (_seriesState.value.selectedSeasonId == requestedSeasonId) {
                    _seriesState.value = _seriesState.value.copy(
                        episodes = result.items.filter { it.type == MediaType.EPISODE },
                        episodesLoading = false,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_seriesState.value.selectedSeasonId == requestedSeasonId) {
                    _seriesState.value = _seriesState.value.copy(
                        episodesLoading = false,
                        episodesError = userMessage(e),
                    )
                }
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ProviderException -> e.message ?: "加载失败"
        else -> "加载失败：${e.message}"
    }

    /**
     * 1F C1（ADR-038）：sibling source 解析为旁路异步状态——主内容绝不等待；
     * 解析失败只在 sourceState 内表达（partial），绝不把已正常打开的 Detail
     * 变成 Error。route 销毁/切换 = viewModelScope 取消传导，全部解析终止。
     */
    private fun startSourceResolution(item: MediaItem) {
        sourceJob?.cancel()
        if (item.type != MediaType.MOVIE && item.type != MediaType.SERIES) {
            _sourceState.value = SourceResolutionState.Idle
            return
        }
        sourceJob = viewModelScope.launch {
            _sourceState.value = SourceResolutionState.Resolving
            _sourceState.value = when (val resolution = sourceResolver.resolve(item, serverId)) {
                is SourceResolution.Idle -> SourceResolutionState.Idle
                is SourceResolution.Completed -> SourceResolutionState.Resolved(resolution)
            }
        }
    }

    companion object {
        /** 季排序：普通季(1,2,3…)→Specials(seasonNumber=0)→未知(seasonNumber=null) */
        fun sortSeasons(seasons: List<MediaItem>): List<MediaItem> {
            val normal = seasons.filter { (it.seasonNumber ?: 0) > 0 }
                .sortedBy { it.seasonNumber }
            val specials = seasons.filter { (it.seasonNumber ?: -1) == 0 }
            val unknown = seasons.filter { it.seasonNumber == null }
            return normal + specials + unknown
        }
    }
}

data class SeriesBrowseState(
    val seasons: List<MediaItem> = emptyList(),
    val selectedSeasonId: String? = null,
    val episodes: List<MediaItem> = emptyList(),
    val seasonsLoading: Boolean = false,
    val episodesLoading: Boolean = false,
    val seasonsError: String? = null,
    val episodesError: String? = null,
    val libraryUnavailable: Boolean = false,
)

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Content(val detail: MediaDetail) : DetailUiState
    data class Error(val message: String) : DetailUiState
}

/**
 * 1F C1：sibling source 解析状态（独立于 [DetailUiState]，
 * ADR-038：current detail load failure = fatal；sibling failure = partial only）。
 */
sealed interface SourceResolutionState {
    data object Idle : SourceResolutionState
    data object Resolving : SourceResolutionState
    data class Resolved(val resolution: SourceResolution.Completed) :
        SourceResolutionState
}