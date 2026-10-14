package com.mediahub.model

/**
 * 播放器视觉效果预置。
 *
 * 关闭状态只由 [PlayerVisualEffectsPreferences.enabled] 表达，因而关闭功能时仍会保留
 * 用户最后选择的预置，重新开启后无需猜测或重置选择。
 */
enum class PlayerVisualPreset {
    AURORA,
    LIQUID,
    SPECTRUM,
}

/** 播放器视觉效果的性能策略。 */
enum class VisualPerformanceMode {
    AUTO,
    BATTERY,
    BALANCED,
    HIGH,
}

/**
 * 可持久化的播放器视觉效果偏好。
 *
 * 默认值让新用户能够发现功能，同时使用克制的强度，避免视觉层干扰视频主体。
 * 来自存储、导入或 UI 的值在进入生产状态前应调用 [normalized]。
 */
data class PlayerVisualEffectsPreferences(
    val enabled: Boolean = true,
    val preset: PlayerVisualPreset = PlayerVisualPreset.AURORA,
    val intensity: Float = DEFAULT_INTENSITY,
    val followArtworkColors: Boolean = true,
    val audioReactive: Boolean = true,
    val performanceMode: VisualPerformanceMode = VisualPerformanceMode.AUTO,
) {
    /** 为渲染层提供语义明确的启停状态。 */
    val isEffectivelyEnabled: Boolean
        get() = enabled

    /**
     * 把不可信强度收敛到生产范围；NaN 使用克制的默认值，无穷值落到边界。
     */
    fun normalized(): PlayerVisualEffectsPreferences {
        val safeIntensity = when {
            intensity.isNaN() -> DEFAULT_INTENSITY
            intensity == Float.POSITIVE_INFINITY -> MAX_INTENSITY
            intensity == Float.NEGATIVE_INFINITY -> MIN_INTENSITY
            else -> intensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        }
        return if (safeIntensity == intensity) this else copy(intensity = safeIntensity)
    }

    companion object {
        const val MIN_INTENSITY: Float = 0f
        const val MAX_INTENSITY: Float = 1f
        const val DEFAULT_INTENSITY: Float = 0.35f

        /** 当前产品默认值；供恢复默认和测试使用。 */
        val Default: PlayerVisualEffectsPreferences
            get() = PlayerVisualEffectsPreferences()
    }
}
