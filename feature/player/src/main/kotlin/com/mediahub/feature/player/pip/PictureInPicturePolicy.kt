package com.mediahub.feature.player.pip

import com.mediahub.model.MediaType

/** PiP 窗口比例，保持为可约分的整数对，便于直接映射到 Android `Rational`。 */
data class PipAspectRatio(val width: Int, val height: Int) {
    val ratio: Float get() = width.toFloat() / height.toFloat()
}

/** PiP 窗口内的三个动作（条目 123）。 */
enum class PipCommand { REWIND, TOGGLE_PLAY_PAUSE, FORWARD }

/**
 * 画中画能力策略（条目 30 / 31 / 113 / 123 / 137）。
 *
 * 这里只放可被 JVM 测试证明的判定；真正的 `enterPictureInPictureMode` 与系统窗口
 * 表现属于真机验证范围，见 handoff 的 `NOT_RUN` 记录。
 */
object PictureInPicturePolicy {

    const val MIN_SUPPORTED_SDK = 26

    /** 与播放器手势层一致的快进/快退步长。 */
    const val SEEK_STEP_MS = 10_000L

    /** Android 允许的 PiP 宽高比下限（约 1:2.39）。 */
    const val MIN_ASPECT_RATIO = 1f / 2.39f

    /** Android 允许的 PiP 宽高比上限（约 2.39:1）。 */
    const val MAX_ASPECT_RATIO = 2.39f

    val DEFAULT_ASPECT_RATIO = PipAspectRatio(16, 9)

    private val MIN_ASPECT = PipAspectRatio(100, 239)
    private val MAX_ASPECT = PipAspectRatio(239, 100)

    fun isSupported(sdkInt: Int, hasSystemFeature: Boolean): Boolean =
        sdkInt >= MIN_SUPPORTED_SDK && hasSystemFeature

    /**
     * 后台自动进入 PiP 的唯一判定。暂停、解析未完成、开关关闭、已在 PiP 中都不应再次进入。
     */
    fun shouldAutoEnter(
        supported: Boolean,
        autoPictureInPicture: Boolean,
        isPlaying: Boolean,
        resolveReady: Boolean,
        alreadyInPictureInPicture: Boolean,
    ): Boolean = supported &&
        autoPictureInPicture &&
        isPlaying &&
        resolveReady &&
        !alreadyInPictureInPicture

    /**
     * API 26–30 没有 `setAutoEnterEnabled`，只能在 `onUserLeaveHint` 里手动进入；
     * API 31+ 交给系统接管，避免重复进入导致窗口抖动。
     */
    fun shouldEnterOnUserLeaveHint(
        sdkInt: Int,
        supported: Boolean,
        autoPictureInPicture: Boolean,
        isPlaying: Boolean,
        resolveReady: Boolean,
        alreadyInPictureInPicture: Boolean,
    ): Boolean = sdkInt < 31 && shouldAutoEnter(
        supported = supported,
        autoPictureInPicture = autoPictureInPicture,
        isPlaying = isPlaying,
        resolveReady = resolveReady,
        alreadyInPictureInPicture = alreadyInPictureInPicture,
    )

    fun canShowEnterButton(supported: Boolean, resolveReady: Boolean): Boolean =
        supported && resolveReady

    /**
     * PiP 窗口使用视频自身比例。窗口与视频同比例时，字幕的底部百分比位置在
     * 全屏与 PiP 之间保持不变（条目 137 的画中画部分）。
     */
    fun aspectRatio(videoWidth: Int, videoHeight: Int): PipAspectRatio {
        if (videoWidth <= 0 || videoHeight <= 0) return DEFAULT_ASPECT_RATIO
        val ratio = videoWidth.toDouble() / videoHeight.toDouble()
        return when {
            ratio > MAX_ASPECT_RATIO -> MAX_ASPECT
            ratio < MIN_ASPECT_RATIO -> MIN_ASPECT
            else -> reduce(videoWidth, videoHeight)
        }
    }

    /** 条目 113：PiP 内连播仍然受「自动连播下一集」开关与条目类型约束。 */
    fun allowsAutoAdvance(autoPlayNextEpisode: Boolean, itemType: MediaType?): Boolean =
        autoPlayNextEpisode && itemType == MediaType.EPISODE

    /** 返回 [command] 对应的目标位置；播放/暂停不是 seek，返回 null。 */
    fun seekTargetMs(positionMs: Long, durationMs: Long, command: PipCommand): Long? = when (command) {
        PipCommand.REWIND -> (positionMs - SEEK_STEP_MS).coerceAtLeast(0L)
        PipCommand.FORWARD -> if (durationMs > 0L) {
            (positionMs + SEEK_STEP_MS).coerceAtMost(durationMs)
        } else {
            positionMs + SEEK_STEP_MS
        }

        PipCommand.TOGGLE_PLAY_PAUSE -> null
    }

    fun commands(): List<PipCommand> =
        listOf(PipCommand.REWIND, PipCommand.TOGGLE_PLAY_PAUSE, PipCommand.FORWARD)

    fun commandFor(name: String?): PipCommand? =
        PipCommand.entries.firstOrNull { it.name == name }

    /**
     * 只有系统真正渲染的参数变化才需要重新 `setPictureInPictureParams`；
     * 播放位置每秒变化数十次，若跟着刷新会持续抖动窗口动作区。
     */
    fun shouldRefreshParams(
        previous: PictureInPictureSessionState,
        next: PictureInPictureSessionState,
    ): Boolean = previous.playerActive != next.playerActive ||
        previous.autoPictureInPicture != next.autoPictureInPicture ||
        previous.isPlaying != next.isPlaying ||
        previous.resolveReady != next.resolveReady ||
        previous.title != next.title ||
        previous.videoWidth != next.videoWidth ||
        previous.videoHeight != next.videoHeight ||
        // 进出 PiP 必须重算自动进入开关：在窗口中要关掉，回到全屏后要恢复。
        previous.inPictureInPicture != next.inPictureInPicture

    private fun reduce(width: Int, height: Int): PipAspectRatio {
        val divisor = gcd(width, height)
        return PipAspectRatio(width / divisor, height / divisor)
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
