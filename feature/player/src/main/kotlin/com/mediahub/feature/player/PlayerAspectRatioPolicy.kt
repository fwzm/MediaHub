package com.mediahub.feature.player

import com.mediahub.model.PlayerAspectRatio

/** 纯函数：把画面比例偏好换算成播放 surface 的显示约束。 */
object PlayerAspectRatioPolicy {
    fun surfaceAspectRatio(mode: PlayerAspectRatio, videoWidth: Int, videoHeight: Int): Float? = when (mode) {
        PlayerAspectRatio.FIT -> if (videoWidth > 0 && videoHeight > 0) {
            videoWidth.toFloat() / videoHeight.toFloat()
        } else {
            null
        }
        PlayerAspectRatio.FILL -> null
        PlayerAspectRatio.RATIO_16_9 -> 16f / 9f
        PlayerAspectRatio.RATIO_4_3 -> 4f / 3f
    }

    /** 在给定容器内按 [aspectRatio] 居中适配；null 或非法比例表示铺满容器。 */
    fun fitSize(containerWidth: Float, containerHeight: Float, aspectRatio: Float?): Pair<Float, Float>? {
        if (containerWidth <= 0f || containerHeight <= 0f) return null
        if (aspectRatio == null || !aspectRatio.isFinite() || aspectRatio <= 0f) {
            return containerWidth to containerHeight
        }
        val containerAspect = containerWidth / containerHeight
        return if (aspectRatio > containerAspect) {
            containerWidth to containerWidth / aspectRatio
        } else {
            containerHeight * aspectRatio to containerHeight
        }
    }
}
