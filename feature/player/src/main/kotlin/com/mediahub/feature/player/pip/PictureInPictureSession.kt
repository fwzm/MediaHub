package com.mediahub.feature.player.pip

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放器与 Activity/PiP 广播之间的会话状态。
 *
 * 播放页负责写入当前播放快照；MainActivity 读取它决定是否允许后台自动进入 PiP；
 * PiP 窗口按钮的广播接收器通过 [dispatch] 把动作送回播放页注册的处理器。
 *
 * 这只是进程内状态，不持有 Context，便于在 JVM 上直接测试。
 */
data class PictureInPictureSessionState(
    val playerActive: Boolean = false,
    val inPictureInPicture: Boolean = false,
    val autoPictureInPicture: Boolean = true,
    val isPlaying: Boolean = false,
    val resolveReady: Boolean = false,
    val title: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

@Singleton
class PictureInPictureSession @Inject constructor() {

    private val _state = MutableStateFlow(PictureInPictureSessionState())
    val state: StateFlow<PictureInPictureSessionState> = _state.asStateFlow()

    private var commandHandler: ((PipCommand) -> Unit)? = null

    fun update(transform: (PictureInPictureSessionState) -> PictureInPictureSessionState) {
        _state.value = transform(_state.value)
    }

    fun setInPictureInPicture(inPictureInPicture: Boolean) {
        update { it.copy(inPictureInPicture = inPictureInPicture) }
    }

    /** 播放页注册动作处理器；离开播放页时必须清空，避免广播打到已销毁的引擎。 */
    fun setCommandHandler(handler: ((PipCommand) -> Unit)?) {
        commandHandler = handler
    }

    /** 离开播放页：关闭自动进入条件并解绑动作处理器。 */
    fun clearPlayer() {
        commandHandler = null
        update {
            it.copy(
                playerActive = false,
                isPlaying = false,
                resolveReady = false,
                title = null,
            )
        }
    }

    @Synchronized
    fun dispatch(command: PipCommand): Boolean {
        val handler = commandHandler ?: return false
        handler(command)
        return true
    }
}
