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
import com.mediahub.model.PlaybackProgress
import com.mediahub.player.engine.PlaybackEngine
import com.mediahub.player.engine.PlaybackEngineFactory
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
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

    /** 引擎绑定到 ViewModel 作用域，onCleared 时释放。 */
    val engine: PlaybackEngine = engineFactory.create(viewModelScope)

    private val _resolveState = MutableStateFlow<ResolveState>(ResolveState.Resolving)
    val resolveState: StateFlow<ResolveState> = _resolveState.asStateFlow()

    private var provider: MediaProvider? = null

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
        engine.onProgressTick = ::handleProgress
        resolve()
    }

    /** 解析播放源并起播：server → provider → getItemDetail → resolvePlayback。 */
    fun resolve() {
        viewModelScope.launch {
            _resolveState.value = ResolveState.Resolving
            try {
                val server = serverRepository.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val providerInstance = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                provider = providerInstance

                val detail = providerInstance.getItemDetail(itemId)
                val resume = progressRepository.getResume(serverId, itemId)
                val source = providerInstance.resolvePlayback(
                    detail.item,
                    PlaybackOptions(startPositionMs = resume, enableDirectPlay = true),
                )
                val session = PlaybackSession(
                    serverId = serverId,
                    itemId = itemId,
                    itemTitle = detail.item.title.ifBlank { itemTitle },
                    source = source,
                    resumePositionMs = resume,
                    itemType = detail.item.type,
                )
                engine.play(session)
                _resolveState.value = ResolveState.Ready
            } catch (e: Exception) {
                logger.w(LogTag.PLAYER, "播放解析失败 serverId=$serverId itemId=$itemId", e)
                _resolveState.value = ResolveState.Failed(userMessage(e))
            }
        }
    }

    private fun handleProgress(progress: PlaybackProgress) {
        viewModelScope.launch {
            // 本地快照（"继续观看"）
            runCatching { progressRepository.save(progress) }
                .onFailure { logger.w(LogTag.PLAYER, "进度快照保存失败", it) }
            // 服务端进度上报（尽力而为，失败不打断播放）
            provider?.let { p ->
                runCatching { p.reportProgress(progress) }
                    .onFailure { logger.w(LogTag.PLAYER, "进度上报失败", it) }
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ProviderException -> e.message ?: "播放失败"
        else -> "播放失败：${e.message}"
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}
