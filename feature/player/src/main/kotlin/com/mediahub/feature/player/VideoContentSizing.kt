package com.mediahub.feature.player

import com.mediahub.model.VideoAspectMode

/** 视频内容在容器内的目标尺寸（单位与调用方一致，通常为 dp 或 px）。 */
data class VideoContentSize(val width: Float, val height: Float) {
    val aspectRatio: Float get() = if (height > 0f) width / height else 0f
}

/**
 * 播放器画面尺寸计算（条目 9 / 137）。
 *
 * 关键点：**在首帧之前就按视频宽高比布局**，避免 SurfaceView 被拉伸；
 * 视频尺寸未知（首帧前）时回退到 16:9，而不是铺满容器。
 */
object VideoContentSizing {
    const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f

    fun aspectRatio(
        mode: VideoAspectMode,
        videoWidth: Int,
        videoHeight: Int,
    ): Float = mode.fixedAspectRatio
        ?: if (videoWidth > 0 && videoHeight > 0) {
            videoWidth.toFloat() / videoHeight.toFloat()
        } else {
            DEFAULT_VIDEO_ASPECT_RATIO
        }

    /**
     * - FIT / 固定比例：完整显示，允许黑边；
     * - CROP：保持比例填满容器，超出部分由外层裁切。
     */
    fun size(
        mode: VideoAspectMode,
        containerWidth: Float,
        containerHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
    ): VideoContentSize {
        if (containerWidth <= 0f || containerHeight <= 0f) return VideoContentSize(0f, 0f)
        val ratio = aspectRatio(mode, videoWidth, videoHeight)
        val containerRatio = containerWidth / containerHeight
        val containerIsWider = containerRatio >= ratio
        return if (mode == VideoAspectMode.CROP) {
            // 填满：容器更宽时按宽度铺满（上下裁切），否则按高度铺满（左右裁切）。
            if (containerIsWider) {
                VideoContentSize(containerWidth, containerWidth / ratio)
            } else {
                VideoContentSize(containerHeight * ratio, containerHeight)
            }
        } else {
            // 完整显示：容器更宽时按高度适配（左右黑边），否则按宽度适配（上下黑边）。
            if (containerIsWider) {
                VideoContentSize(containerHeight * ratio, containerHeight)
            } else {
                VideoContentSize(containerWidth, containerWidth / ratio)
            }
        }
    }
}
