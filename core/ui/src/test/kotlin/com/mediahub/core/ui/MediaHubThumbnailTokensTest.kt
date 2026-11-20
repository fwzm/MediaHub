package com.mediahub.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 视觉审计 §3.3：缩略图尺寸必须使用统一 token，禁止各页面各自写 120/96/110 等一次性值。 */
class MediaHubThumbnailTokensTest {

    @Test
    fun posterListWidthIs96Dp() {
        assertEquals(96, MediaHubThumbnailTokens.POSTER_WIDTH_DP)
        assertNotEquals(120, MediaHubThumbnailTokens.POSTER_WIDTH_DP)
    }

    @Test
    fun thumb16x9WidthIs96Dp() {
        assertEquals(96, MediaHubThumbnailTokens.THUMB_16X9_WIDTH_DP)
        assertNotEquals(120, MediaHubThumbnailTokens.THUMB_16X9_WIDTH_DP)
    }

    @Test
    fun posterHeroWidthIs104Dp() {
        assertEquals(104, MediaHubThumbnailTokens.POSTER_HERO_WIDTH_DP)
        assertNotEquals(110, MediaHubThumbnailTokens.POSTER_HERO_WIDTH_DP)
    }
}
