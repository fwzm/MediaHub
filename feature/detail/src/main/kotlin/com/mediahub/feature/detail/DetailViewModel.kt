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
import com.mediahub.metadata.MetadataDetail
import com.mediahub.metadata.MetadataEngine
import com.mediahub.metadata.match.MatchCandidate
import com.mediahub.metadata.match.MatchFailure
import com.mediahub.metadata.match.MetadataMatch
import com.mediahub.metadata.match.MetadataMatchStore
import com.mediahub.metadata.ratings.PlatformRating
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
    private val metadataEngine: MetadataEngine,
    private val matchStore: MetadataMatchStore,
) : ViewModel() {

    val serverId: String = checkNotNull(savedStateHandle["serverId"])
    val itemId: String = NavArgCodec.decode(checkNotNull(savedStateHandle["itemId"]))

    private val _uiState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val _seriesState = MutableStateFlow(SeriesBrowseState())
    val seriesState: StateFlow<SeriesBrowseState> = _seriesState.asStateFlow()

    private val _sourceState = MutableStateFlow<SourceResolutionState>(SourceResolutionState.Idle)
    val sourceState: StateFlow<SourceResolutionState> = _sourceState.asStateFlow()

    /** 收藏开关（task-25 / A2）：null = 该数据源未提供收藏能力，界面不显示开关。 */
    private val _favoriteState = MutableStateFlow<FavoriteToggleState?>(null)
    val favoriteState: StateFlow<FavoriteToggleState?> = _favoriteState.asStateFlow()

    private var handle: ProviderHandle? = null
    private var episodeJob: Job? = null
    private var sourceJob: Job? = null
    private var matchJob: Job? = null

    /** 刮削状态：引擎未配置密钥时 available=false，UI 隐藏入口且不发起任何外部请求。 */
    private val _metadataState = MutableStateFlow(DetailMetadataState(available = metadataEngine.available))
    val metadataState: StateFlow<DetailMetadataState> = _metadataState.asStateFlow()

    /** 手动匹配弹窗状态。 */
    private val _matchState = MutableStateFlow(ManualMatchState())
    val matchState: StateFlow<ManualMatchState> = _matchState.asStateFlow()

    init { load() }

    fun load() {
        _uiState.value = DetailUiState.Loading
        _favoriteState.value = null
        _metadataState.value = DetailMetadataState(available = metadataEngine.available)
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
                // 收藏状态取自服务器 UserData（本机无收藏表）；无 favorite 能力的源不显示开关
                if (h.favorite != null) {
                    _favoriteState.value = FavoriteToggle.fromServerState(detail.item.id, detail.item.isFavorite)
                }
                startSourceResolution(detail.item)
                restoreMetadataMatch()

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

    /**
     * 切换收藏（task-25 / A2）：写端点成功后**再读回**，界面只认回读值。
     * 失败保持原状态并给出可读错误（不把图标切成相反状态）。
     */
    fun toggleFavorite() {
        val current = _favoriteState.value ?: return
        if (current.isUpdating) return
        val favoriteProvider = handle?.favorite ?: return
        val target = !current.isFavorite
        _favoriteState.value = FavoriteToggle.pending(current)
        viewModelScope.launch {
            try {
                val serverValue = favoriteProvider.setFavorite(current.itemId, target)
                _favoriteState.value = FavoriteToggle.confirmed(current, serverValue)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.w(LogTag.UI, "收藏切换失败 serverId=$serverId itemId=${current.itemId}", e)
                _favoriteState.value = FavoriteToggle.failed(current, userMessage(e))
            }
        }
    }

    // ---- 手动匹配（条目 51 / 67）----

    /** 引擎未配置密钥时不开弹窗、不显示入口（降级而非崩溃）。 */
    fun openMatchDialog() {
        if (!metadataEngine.available) return
        val item = (uiState.value as? DetailUiState.Content)?.detail?.item ?: return
        _matchState.value = ManualMatchState(visible = true, query = item.title)
    }

    fun dismissMatchDialog() {
        _matchState.value = _matchState.value.copy(visible = false)
    }

    fun onMatchQueryChange(query: String) {
        _matchState.value = _matchState.value.copy(query = query)
    }

    /** 调用上一轮的 ManualMatchService（归一化 + 年份/语言排序），UI 不做任何匹配计算。 */
    fun searchMetadataMatch() {
        if (!metadataEngine.available) return
        val snapshot = _matchState.value
        if (snapshot.query.isBlank()) return
        val item = (uiState.value as? DetailUiState.Content)?.detail?.item
        matchJob?.cancel()
        _matchState.value = snapshot.copy(
            searching = true,
            error = null,
            candidates = emptyList(),
            failures = emptyList(),
            ambiguous = false,
        )
        matchJob = viewModelScope.launch {
            try {
                val outcome = metadataEngine.manualMatch.search(
                    query = snapshot.query,
                    year = item?.year,
                    preferredLanguage = null,
                )
                val error = when {
                    outcome.candidates.isNotEmpty() -> null
                    outcome.failures.isNotEmpty() -> "刮削服务请求失败"
                    else -> "未找到匹配"
                }
                _matchState.value = _matchState.value.copy(
                    searching = false,
                    candidates = outcome.candidates,
                    failures = outcome.failures,
                    ambiguous = outcome.ambiguous,
                    error = error,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.w(LogTag.UI, "手动匹配搜索失败 serverId=$serverId itemId=$itemId", e)
                _matchState.value = _matchState.value.copy(searching = false, error = "搜索失败")
            }
        }
    }

    /** 人工确认：写回匹配结果（SharedPreferences），随后加载元数据详情。 */
    fun applyMetadataMatch(candidate: MatchCandidate) {
        val result = candidate.result
        val match = MetadataMatch(
            source = result.source,
            metadataId = result.id,
            title = result.title,
            year = result.year,
            posterUrl = result.posterUrl,
            mediaType = result.mediaType,
        )
        _matchState.value = _matchState.value.copy(visible = false)
        _metadataState.value = _metadataState.value.copy(match = match)
        viewModelScope.launch {
            try {
                matchStore.put(serverId, itemId, match)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.w(LogTag.UI, "匹配结果写入失败 serverId=$serverId itemId=$itemId", e)
            }
            loadMetadataDetail(match)
        }
    }

    private fun restoreMetadataMatch() {
        if (!metadataEngine.available) return
        viewModelScope.launch {
            val match = runCatching { matchStore.get(serverId, itemId) }.getOrNull() ?: return@launch
            _metadataState.value = _metadataState.value.copy(match = match)
            loadMetadataDetail(match)
        }
    }

    /** 元数据详情/评分加载失败只降级为空，不影响已打开的详情页。 */
    private fun loadMetadataDetail(match: MetadataMatch) {
        viewModelScope.launch {
            try {
                val provider = metadataEngine.registry.providerFor(match.source) ?: return@launch
                val metadataDetail = provider.detail(match.metadataId) ?: return@launch
                _metadataState.value = _metadataState.value.copy(detail = metadataDetail)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.w(
                    LogTag.UI,
                    "元数据详情加载失败 source=${match.source} id=${match.metadataId}",
                    e,
                )
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        // ProviderException.message 为消毒后的固定文案（A3-4），可透出；
        // 其余异常的 message 可能携带 URL/凭据等敏感文本，只给固定分类。
        is ProviderException -> e.message ?: "加载失败"
        else -> "加载失败"
    }

    /**
     * 1F C1（ADR-038）：sibling source 解析为旁路异步状态——主内容绝不等待；
     * 解析失败只在 sourceState 内表达（partial），绝不把已正常打开的 Detail
     * 变成 Error。route 销毁/切换 = viewModelScope 取消传导，全部解析终止。
     *
     * 1G-C eligibility guard（ADR-039 review 冻结）：当前 provider 的 Handle 无
     * IDENTITY_LOOKUP 能力（如 Jellyfin）时 sourceState 停在 Idle、不启动 resolver——
     * 防止单向 source switching（Jellyfin Detail 能看见 Emby sibling、反向不可达）。
     * Emby（identityLookup != null）行为不变。
     */
    private fun startSourceResolution(item: MediaItem) {
        sourceJob?.cancel()
        if (item.type != MediaType.MOVIE && item.type != MediaType.SERIES) {
            _sourceState.value = SourceResolutionState.Idle
            return
        }
        if (handle?.identityLookup == null) {
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

/** 刮削状态（密钥缺失时 available=false，评分列表为空且 UI 不占位）。 */
data class DetailMetadataState(
    val available: Boolean = false,
    val match: MetadataMatch? = null,
    val detail: MetadataDetail? = null,
    val ratings: List<PlatformRating> = emptyList(),
    val averageRating: Double? = null,
    val ratingsLoading: Boolean = false,
)

/** 手动匹配弹窗状态。 */
data class ManualMatchState(
    val visible: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val candidates: List<MatchCandidate> = emptyList(),
    val failures: List<MatchFailure> = emptyList(),
    val ambiguous: Boolean = false,
    val error: String? = null,
)

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