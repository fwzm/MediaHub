package com.mediahub.model

/**
 * 播放内核选择（U3-A）：AUTO = 兼容性评估 + 失败指纹自动选 Media3/mpv。
 */
enum class PlaybackEngineMode { AUTO, MEDIA3, MPV }

/**
 * 用户播放偏好（持久化于 DataStore，见 core:database）。
 * 播放兼容性评估器（player:compatibility）与播放器 UI 都消费该模型。
 */
data class UserPreferences(
    val playbackEngineMode: PlaybackEngineMode = PlaybackEngineMode.AUTO,
    val defaultPlaybackSpeed: Float = 1f,
    val subtitleSizeSp: Int = 18,
    val enableHardwareDecoding: Boolean = true,
    val preferDirectPlay: Boolean = true,
    val autoPlayNextEpisode: Boolean = true,
    val maxBitrateBps: Long? = null,
    val showPlayerInfoOverlay: Boolean = false,
    /** 播放视频时自动横屏（进入播放器锁定 SENSOR_LANDSCAPE，退出恢复原方向）。 */
    val autoLandscape: Boolean = true,
    /** 播放时隐藏状态栏/导航栏（沉浸式；边缘滑动可临时唤出）。 */
    val immersiveBars: Boolean = true,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** 播放器手势（U3-B，独立于 defaultPlaybackSpeed，不复用）。 */
    val gestures: PlayerGestures = PlayerGestures(),
    /** 播放器视觉效果：全局默认、预置、强度、媒体配色、音频响应与性能策略。 */
    val playerVisualEffects: PlayerVisualEffectsPreferences = PlayerVisualEffectsPreferences.Default,
)

/**
 * 播放器手势偏好（U3-B，9 项）。
 *
 * - 水平滑动快进快退（scrub）：松手才 commit seek；
 *   灵敏度 = clamp(总时长 × 10%, 60s, 10min)，目标位置 clamp 0..duration。
 * - 双击矩阵：左/右半屏各自可启用快退/快进（默认关，5-60s 默认 10s）；
 *   未启用 seek 的一侧双击 = 播放/暂停。
 * - 双击左侧后按住不放 = 连续快退（每秒约 3 次 PREVIEW seek，松手 COMMIT），
 *   随双击快退开关启用。
 * - 长按临时倍速：方向/幅度锚点在按下瞬间锁定，水平拖动沿阶梯调倍率，
 *   松开恢复长按前的永久倍速（而非 1.0×）；下限 0.5×/0.1×，上限固定 5.0×。
 */
data class PlayerGestures(
    /** 水平滑动快进快退。 */
    val scrubEnabled: Boolean = true,
    /** 双击左半屏快退（默认关）。 */
    val doubleTapSeekBackwardEnabled: Boolean = false,
    /** 双击快退秒数（5-60，默认 10）。 */
    val doubleTapSeekBackwardSeconds: Int = 10,
    /** 双击右半屏快进（默认关）。 */
    val doubleTapSeekForwardEnabled: Boolean = false,
    /** 双击快进秒数（5-60，默认 10）。 */
    val doubleTapSeekForwardSeconds: Int = 10,
    /** 长按临时倍速。 */
    val longPressSpeedEnabled: Boolean = true,
    /** 长按倍速下限（0.5× 或 0.1×）。 */
    val longPressSpeedMin: Float = 0.5f,
    /** 长按倍速上限（规格固定 5.0×；保留字段便于校准）。 */
    val longPressSpeedMax: Float = 5.0f,
    /** 长按左侧快退/右侧快进方向模式（Phase 1B-2.4 U3-B revision）。关闭后两侧均正向倍速。 */
    val longPressDirectionalEnabled: Boolean = true,
    /** 长按默认倍率（1.0-4.0，默认 2.0×；启动时锁定此档开始）。 */
    val longPressDefaultSpeed: Float = 2.0f,
)

/**
 * 字幕样式（Phase 1B-2.4；默认白字 + 全透明背景 + 黑描边，见 ADR-032）。
 * 颜色为 ARGB Int；edgeType 取 SubtitleStyle.EDGE_*。
 */
data class SubtitleStyle(
    val textColor: Int = 0xFFFFFFFF.toInt(),
    /** 默认全透明（修复"电视 CC 黑底"观感）。 */
    val backgroundColor: Int = 0x00000000,
    val edgeType: Int = EDGE_TYPE_OUTLINE,
    val edgeColor: Int = 0xFF000000.toInt(),
    /** 视高比例的字号缩放（相对默认 18sp 档位）。 */
    val textScale: Float = 1f,
    /** 字幕底部距视频底边的比例（0.0~0.4）。 */
    val bottomPaddingFraction: Float = 0.08f,
    /** 尊重内嵌 ASS/PGS 样式（关闭则强制用户样式）。 */
    val applyEmbeddedStyles: Boolean = true,
) {
    companion object {
        const val EDGE_TYPE_NONE = 0
        const val EDGE_TYPE_OUTLINE = 1
        const val EDGE_TYPE_DROP_SHADOW = 2
    }
}
