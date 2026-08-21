package com.mediahub.model

/**
 * 用户播放偏好（持久化于 DataStore，见 core:database）。
 * 播放兼容性评估器（player:compatibility）与播放器 UI 都消费该模型。
 */
data class UserPreferences(
    val defaultPlaybackSpeed: Float = 1f,
    val subtitleSizeSp: Int = 18,
    val enableHardwareDecoding: Boolean = true,
    val preferDirectPlay: Boolean = true,
    val autoPlayNextEpisode: Boolean = true,
    val maxBitrateBps: Long? = null,
    val showPlayerInfoOverlay: Boolean = false,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
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
