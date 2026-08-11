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
)
