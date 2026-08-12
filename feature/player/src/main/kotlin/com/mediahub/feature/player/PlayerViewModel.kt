package com.mediahub.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.AppDispatchers
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.repository.ProgressRepository
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackProgressReason
import com.mediahub.player.engine.PlaybackEngine
import com.mediahub.player.engine.PlaybackEngineFactory
import com.mediahub.player.engine.PlaybackProgressEvent
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ResolveState {
    data object Resolving : ResolveState
    data object Ready : ResolveState
    data class Failed(val message: String) : ResolveState
}

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
    private val engineFactory: PlaybackEngineFactory,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    private val itemId: String = NavArgCodec.decode(checkNotNull(savedStateHandle["itemId"]))
    private val itemTitle: String = savedStateHandle["title"] ?: ""

    val engine: PlaybackEngine = engineFactory.create(viewModelScope)

    private val _resolveState = MutableStateFlow<ResolveState>(ResolveState.Resolving)
    val resolveState: StateFlow<ResolveState> = _resolveState.asStateFlow()

    private val progressGate = ProgressSyncGate()
    private var providerHandle: ProviderHandle? = null

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
        observeProgressPipeline()
        resolve()
    }

    fun resolve() {
        viewModelScope.launch {
            _resolveState.value = ResolveState.Resolving
            try {
                val server = serverRepository.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val handle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                val playback = handle.playback
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源播放能力")
                providerHandle = handle

                val item = handle.library?.getItemDetail(itemId)?.item ?: MediaItem(
                    serverId = serverId,
                    id = itemId,
                    type = MediaType.OTHER,
                    title = itemTitle.ifBlank { itemId.substringAfterLast('/') },
                    path = itemId,
                )
                val resume = progressRepository.getResume(serverId, itemId)
                val source = playback.resolvePlayback(
                    item,
                    PlaybackOptions(startPositionMs = resume, enableDirectPlay = true),
                )
                engine.play(
                    PlaybackSession(
                        serverId = serverId,
                        itemId = itemId,
                        itemTitle = item.title.ifBlank { itemTitle },
                        source = source,
                        resumePositionMs = resume,
                        itemType = item.type,
                    )
                )
                _resolveState.value = ResolveState.Ready
            } catch (e: Exception) {
                logger.w(LogTag.PLAYER, "播放解析失败 serverId=$serverId itemId=$itemId", e)
                _resolveState.value = ResolveState.Failed(userMessage(e))
            }
        }
    }

    /** 返回前先完成 final flush，再释放页面。 */
    fun stopAndExit(onStopped: () -> Unit) {
        viewModelScope.launch {
            engine.currentProgress()?.let { syncCritical(it, PlaybackProgressReason.STOP) }
            engine.stop(publishEvent = false)
            onStopped()
        }
    }

    private fun observeProgressPipeline() {
        viewModelScope.launch {
            engine.progress.filterNotNull().conflate().collect { progress ->
                val remoteInterval = providerHandle?.progress?.reportingPolicy?.periodicIntervalMs
                val decision = progressGate.onPeriodic(progress.updatedAtEpochMs, remoteInterval)
                if (decision.saveLocal) saveLocal(progress)
                if (decision.reportRemote) reportRemote(progress, PlaybackProgressReason.PERIODIC)
            }
        }
        viewModelScope.launch {
            engine.progressEvents.conflate().collect(::syncCriticalEvent)
        }
    }

    private suspend fun syncCriticalEvent(event: PlaybackProgressEvent) =
        syncCritical(event.progress, event.reason)

    private suspend fun syncCritical(progress: PlaybackProgress, reason: PlaybackProgressReason) {
        progressGate.onCritical(progress.updatedAtEpochMs)
        saveLocal(progress)
        reportRemote(progress, reason)
    }

    private suspend fun saveLocal(progress: PlaybackProgress) {
        runCatching { progressRepository.save(progress) }
            .onFailure { logger.w(LogTag.PLAYER, "进度快照保存失败", it) }
    }

    private suspend fun reportRemote(progress: PlaybackProgress, reason: PlaybackProgressReason) {
        val reporter = providerHandle?.progress ?: return
        runCatching { reporter.reportProgress(progress, reason) }
            .onFailure { logger.w(LogTag.PLAYER, "进度上报失败 reason=$reason", it) }
    }

    private fun userMessage(error: Exception): String = when (error) {
        is ProviderException -> error.message ?: "播放失败"
        else -> "播放失败：${error.message}"
    }

    override fun onCleared() {
        val finalProgress = engine.release()
        val handle = providerHandle
        if (finalProgress != null) {
            val finalJob = SupervisorJob()
            CoroutineScope(finalJob + dispatchers.io).launch {
                try {
                    runCatching { progressRepository.save(finalProgress) }
                    handle?.progress?.let { reporter ->
                        runCatching { reporter.reportProgress(finalProgress, PlaybackProgressReason.STOP) }
                    }
                } finally {
                    finalJob.cancel()
                }
            }
        }
        super.onCleared()
    }
}
