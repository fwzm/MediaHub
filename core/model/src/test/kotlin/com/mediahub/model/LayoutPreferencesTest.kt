package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPreferencesTest {

    @Test
    fun `home section styles encode deterministically regardless of insertion order`() {
        val a = mapOf(
            HomeSectionIds.SERVERS to HomeSectionStyle.ROW,
            HomeSectionIds.CONTINUE_WATCHING to HomeSectionStyle.COMPACT,
        )
        val b = mapOf(
            HomeSectionIds.CONTINUE_WATCHING to HomeSectionStyle.COMPACT,
            HomeSectionIds.SERVERS to HomeSectionStyle.ROW,
        )
        assertEquals(encodeHomeSectionStyles(a), encodeHomeSectionStyles(b))
    }

    @Test
    fun `home section styles round trip`() {
        val styles = mapOf(
            HomeSectionIds.CONTINUE_WATCHING to HomeSectionStyle.CARD,
            HomeSectionIds.SERVERS to HomeSectionStyle.COMPACT,
        )
        assertEquals(styles, decodeHomeSectionStyles(encodeHomeSectionStyles(styles)))
    }

    @Test
    fun `decode drops unknown style names and malformed entries`() {
        val decoded = decodeHomeSectionStyles(
            "continue_watching=COMPACT|servers=NOT_A_STYLE|bad|servers=CARD",
        )
        assertEquals(
            mapOf(
                HomeSectionIds.CONTINUE_WATCHING to HomeSectionStyle.COMPACT,
                HomeSectionIds.SERVERS to HomeSectionStyle.CARD,
            ),
            decoded,
        )
    }

    @Test
    fun `decode tolerates null and blank`() {
        assertTrue(decodeHomeSectionStyles(null).isEmpty())
        assertTrue(decodeHomeSectionStyles("").isEmpty())
    }

    @Test
    fun `library order round trip is deduplicated and ordered`() {
        val ids = listOf("b", "a", "c", "a")
        assertEquals(listOf("b", "a", "c"), decodeLibraryOrder(encodeLibraryOrder(ids)))
    }

    @Test
    fun `apply library order keeps unknown items after known ones in original order`() {
        val items = listOf("x", "c", "a", "y", "b")
        val ordered = applyLibraryOrder(items, listOf("b", "a", "c")) { it }
        assertEquals(listOf("b", "a", "c", "x", "y"), ordered)
    }

    @Test
    fun `apply empty order is identity`() {
        val items = listOf("a", "b")
        assertEquals(items, applyLibraryOrder(items, emptyList()) { it })
    }
}
