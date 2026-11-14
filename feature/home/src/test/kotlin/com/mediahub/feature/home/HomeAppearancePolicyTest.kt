package com.mediahub.feature.home

import com.mediahub.model.HomeSectionIds
import com.mediahub.model.HomeSectionStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAppearancePolicyTest {

    @Test
    fun `unconfigured section defaults to card style`() {
        assertEquals(HomeSectionStyle.CARD, sectionStyleOf(emptyMap(), HomeSectionIds.SERVERS))
    }

    @Test
    fun `each section reads its own independent preference`() {
        val styles = mapOf(
            HomeSectionIds.CONTINUE_WATCHING to HomeSectionStyle.ROW,
            HomeSectionIds.SERVERS to HomeSectionStyle.COMPACT,
        )
        assertEquals(HomeSectionStyle.ROW, sectionStyleOf(styles, HomeSectionIds.CONTINUE_WATCHING))
        assertEquals(HomeSectionStyle.COMPACT, sectionStyleOf(styles, HomeSectionIds.SERVERS))
    }

    @Test
    fun `card width shrinks for denser styles and row uses full width`() {
        assertEquals(276, continueCardWidthDp(HomeSectionStyle.CARD))
        assertEquals(196, continueCardWidthDp(HomeSectionStyle.COMPACT))
        assertEquals(0, continueCardWidthDp(HomeSectionStyle.ROW))
    }

    @Test
    fun `style labels are distinct`() {
        val labels = HomeSectionStyle.entries.map(::homeStyleLabel)
        assertEquals(HomeSectionStyle.entries.size, labels.toSet().size)
    }
}
