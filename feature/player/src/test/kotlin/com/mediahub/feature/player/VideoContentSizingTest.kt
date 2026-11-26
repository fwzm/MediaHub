package com.mediahub.feature.player

import com.mediahub.model.VideoAspectMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 条目 9 / 137：首帧按真实宽高比布局，比例模式可切换。 */
class VideoContentSizingTest {
    private val epsilon = 0.001f

    @Test
    fun `unknown video size falls back to 16 by 9 instead of stretching to fill`() {
        val size = VideoContentSizing.size(
            mode = VideoAspectMode.FIT,
            containerWidth = 1080f,
            containerHeight = 2400f,
            videoWidth = 0,
            videoHeight = 0,
        )
        assertEquals(1080f, size.width, epsilon)
        assertEquals(1080f / (16f / 9f), size.height, epsilon)
        assertTrue("letterboxed, not full height", size.height < 2400f)
    }

    @Test
    fun `fit keeps 4 by 3 content complete inside a wide container`() {
        val size = VideoContentSizing.size(VideoAspectMode.FIT, 2400f, 1080f, 1440, 1080)
        assertEquals(1080f, size.height, epsilon)
        assertEquals(1440f, size.width, epsilon)
    }

    @Test
    fun `fit uses height first for a wide video in a tall container`() {
        val size = VideoContentSizing.size(VideoAspectMode.FIT, 1080f, 2400f, 1920, 1080)
        assertEquals(1080f, size.width, epsilon)
        assertEquals(1080f / (16f / 9f), size.height, epsilon)
    }

    @Test
    fun `crop covers the container and overflows the shorter axis`() {
        val tall = VideoContentSizing.size(VideoAspectMode.CROP, 1080f, 2400f, 1920, 1080)
        assertEquals(2400f, tall.height, epsilon)
        assertTrue("overflows width", tall.width > 1080f)

        val wide = VideoContentSizing.size(VideoAspectMode.CROP, 2400f, 1080f, 1920, 1080)
        assertEquals(2400f, wide.width, epsilon)
        assertTrue("overflows height", wide.height > 1080f)
    }

    @Test
    fun `fixed ratio modes ignore the source size`() {
        val sixteenByNine = VideoContentSizing.size(VideoAspectMode.RATIO_16_9, 1000f, 1000f, 1440, 1080)
        assertEquals(16f / 9f, sixteenByNine.aspectRatio, epsilon)

        val fourByThree = VideoContentSizing.size(VideoAspectMode.RATIO_4_3, 1000f, 1000f, 1920, 1080)
        assertEquals(4f / 3f, fourByThree.aspectRatio, epsilon)
    }

    @Test
    fun `zero container yields zero size without dividing by zero`() {
        val size = VideoContentSizing.size(VideoAspectMode.FIT, 0f, 0f, 1920, 1080)
        assertEquals(0f, size.width, epsilon)
        assertEquals(0f, size.height, epsilon)
    }
}
