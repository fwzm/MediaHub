package com.mediahub.feature.player

import com.mediahub.model.PlayerAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerAspectRatioPolicyTest {
    @Test
    fun `fit uses the decoded video aspect ratio`() {
        val aspect = PlayerAspectRatioPolicy.surfaceAspectRatio(PlayerAspectRatio.FIT, 1920, 1080)
        assertEquals(16f / 9f, aspect!!, 0.0001f)
    }

    @Test
    fun `fill leaves the surface unconstrained`() {
        assertNull(PlayerAspectRatioPolicy.surfaceAspectRatio(PlayerAspectRatio.FILL, 1920, 1080))
    }

    @Test
    fun `fixed ratios ignore the decoded video size`() {
        assertEquals(16f / 9f, PlayerAspectRatioPolicy.surfaceAspectRatio(PlayerAspectRatio.RATIO_16_9, 4, 3)!!, 0.0001f)
        assertEquals(4f / 3f, PlayerAspectRatioPolicy.surfaceAspectRatio(PlayerAspectRatio.RATIO_4_3, 16, 9)!!, 0.0001f)
    }

    @Test
    fun `fit without video dimensions keeps the surface unconstrained`() {
        assertNull(PlayerAspectRatioPolicy.surfaceAspectRatio(PlayerAspectRatio.FIT, 0, 0))
    }

    @Test
    fun `fit size letterboxes wide content inside a square container`() {
        assertEquals(1000f to 750f, PlayerAspectRatioPolicy.fitSize(1000f, 1000f, 4f / 3f))
    }

    @Test
    fun `fit size pillarboxes tall content inside a wide container`() {
        val size = PlayerAspectRatioPolicy.fitSize(1000f, 500f, 4f / 3f)!!
        assertEquals(666.6667f, size.first, 0.001f)
        assertEquals(500f, size.second, 0f)
    }

    @Test
    fun `fit size uses the whole container when the aspect is unconstrained`() {
        assertEquals(1000f to 500f, PlayerAspectRatioPolicy.fitSize(1000f, 500f, null))
    }
}
