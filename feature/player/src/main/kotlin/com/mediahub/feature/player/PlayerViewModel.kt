package com.mediahub.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.repository.ProgressRepository
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.PlaybackOptions
import com.mediahub.player.engine.PlaybackEngine
import com.mediahub.player.engine.PlaybackEngineFactory
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.ProgressSyncCoordinator
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 播放源解析状态。 */
sealed interface ResolveState {
    data object Resolving : ResolveState
    data object Ready : ResolveState
    data class Failed(val message: String) : ResolveState
}

/** 播放页组合状态（解析状态 + 引擎状态）。 */
data class PlayerCombinedState(
    val resolve: ResolveState = ResolveState.Resolving,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val mediaTitle: String? = null,
    val error: com.mediahub.core.network.PlaybackError? = null,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val progressRepository: ProgressRepository,
    private val registry: MediaProviderRegistry,
    engineFactory: PlaybackEngineFactory,
    private val logger: Logger,
) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    // itemId 经 NavArgCodec(Base64 URL_SAFE) 传输，兼容文件路径中的 '/'（见 core:common）
    private val itemId: String = NavArgCodec.decode(checkNotNull(savedStateHandle["itemId"]))
    private val itemTitle: String = savedStateHandle["title"] ?: ""

    /** 引擎绑定到 ViewModel 作用域，onCleared 时释放；请求头上下文 per-engine（ADR-018）。 */
    val engine: PlaybackEngine = engineFactory.create(viewModelScope)

    private val _resolveState = MutableStateFlow<ResolveState>(ResolveState.Resolving)
    val resolveState: StateFlow<ResolveState> = _resolveState.asStateFlow()

    /** 当前 Provider 句柄（能力组合，ADR-014）；resolve 成功后可用。 */
    private var handle: ProviderHandle? = null

    /**
     * 进度同步管线（ADR-017）：本地快照 5s 采样、远端上报按 Provider 间隔、
     * 关键事件（Pause/Seek/Ended）立即同步、退出时 final flush。
     * 不再每秒写库 + 上报。
     */
    private val syncCoordinator = ProgressSyncCoordinator(
        scope = viewModelScope,
        localSave = { progressRepository.save(it) },
        remoteReport = { progress ->
            handle?.progress?.let { runCatching { it.reportProgress(progress) } }
        },
    )
    private var syncStarted = false

    val uiState: StateFlow<PlayerCombinedState> =
        combine(engine.uiState, _resolveState) { player, resolve ->
            PlayerCombinedState(
                resolve = resolve,
                isPlaying = player.isPlaying,
                isBuffering = player.isBuffering,
                positionMs = player.positionMs,
                durationMs = player.durationMs,
                speed = player.speed,
                mediaTitle = player.mediaTitle,
                error = player.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerCombinedState())

    init {
        resolve()
    }

    /** 解析播放源并起播：server → handle → detail → resolvePlayback。 */
    fun resolve() {
        viewModelScope.launch {
            _resolveState.value = ResolveState.Resolving
            try {
                val server = serverRepository.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val providerHandle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                handle = providerHandle

                val detailProvider = providerHandle.detail
                    ?: throw ProviderException.NotYetImplemented(serverId, "该数据源的详情能力尚未接入")
                val playbackProvider = providerHandle.playback
                    ?: throw ProviderException.NotYetImplemented(serverId, "该数据源的播放能力尚未接入")

                val detail = detailProvider.getItemDetail(itemId)
                val resume = progressRepository.getResume(serverId, itemId)
                val source = playbackProvider.resolvePlayback(
                    detail.item,
                    PlaybackOptions(startPositionMs = resume, enableDirectPlay = true),
                )
                engine.play(
                    PlaybackSession(
                        serverId = serverId,
                        itemId = itemId,
                        itemTitle = detail.item.title.ifBlank { itemTitle },
                        source = source,
                        resumePositionMs = resume,
                        itemType = detail.item.type,
                    )
                )

                if (!syncStarted) {
                    syncCoordinator.start(
                        progress = engine.progress,
                        events = engine.events,
                        remoteIntervalMs = providerHandle.progress?.remoteReportIntervalMs
                            ?: 10_000L,
                    )
                    syncStarted = true
                }
                _resolveState.value = ResolveState.Ready
            } catch (e: Exception) {
                logger.w(LogTag.PLAYER, "播放解析失败 serverId=$serverId itemId=$itemId", e)
                _resolveState.value = ResolveState.Failed(userMessage(e))
            }
        }
    }

    /** 播放页离开时的 final flush（由 PlayerScreen onDispose 调用）。 */
    fun flushProgress() {
        viewModelScope.launch { syncCoordinator.flush() }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ProviderException -> e.message ?: "播放失败"
        else -> "播放失败：${e.message}"
    }

    override fun onCleared() {
        syncCoordinator.stop()
        engine.release()
        super.onCleared()
    }
}
