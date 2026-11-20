package com.mediahub.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审计 D5：详情页元信息行统一分隔符 token，禁止手工拼 "  ·  " 与不可控行高。 */
class DetailMetaLineTest {

    @Test
    fun metaTextJoinsKnownFieldsWithSingleSeparator() {
        assertEquals(
            "2021 · 142分钟 · ★ 8.6 · MKV",
            detailMetaText(year = 2021, runtimeMs = 142L * 60_000L, communityRating = 8.6, container = "mkv"),
        )
    }

    @Test
    fun metaTextDropsUnknownOrNonPositiveFields() {
        assertNull(detailMetaText(year = null, runtimeMs = 30_000L, communityRating = null, container = null))
        assertEquals("2020", detailMetaText(year = 2020, runtimeMs = null, communityRating = null, container = null))
    }

    @Test
    fun metaTextFormatsRatingAndUppercasesContainer() {
        val text = detailMetaText(year = null, runtimeMs = 0L, communityRating = 9.04, container = "mkv")
        assertTrue(text != null && text.contains("★ 9.0"))
        assertTrue(text != null && text.contains("MKV"))
    }
}
