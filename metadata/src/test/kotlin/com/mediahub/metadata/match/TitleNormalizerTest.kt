package com.mediahub.metadata.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleNormalizerTest {

    @Test
    fun `strips release tags and dotted separators`() {
        assertEquals(
            "the matrix",
            TitleNormalizer.normalize("The.Matrix.1999.1080p.BluRay.x264-GROUP"),
        )
    }

    @Test
    fun `strips chinese season episode markers and bracket tags`() {
        assertEquals(
            "绝命毒师",
            TitleNormalizer.normalize("【绝命毒师】第一季 S01E01 1080p"),
        )
    }

    @Test
    fun `folds full width characters`() {
        assertEquals("friends", TitleNormalizer.normalize("ＦＲＩＥＮＤＳ"))
    }

    @Test
    fun `normalizes punctuation between latin words`() {
        assertEquals(
            "spider man no way home",
            TitleNormalizer.normalize("Spider-Man: No Way Home (2021)"),
        )
    }

    @Test
    fun `normalizes chinese punctuation around year`() {
        assertEquals("让子弹飞", TitleNormalizer.normalize("让子弹飞（2010）"))
    }

    @Test
    fun `parses season episode year and extension`() {
        val parsed = TitleNormalizer.parse("Show.Name.S02E05.2020.1080p.mkv")
        assertEquals("show name", parsed.title)
        assertEquals(2020, parsed.year)
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun `parses chinese season marker`() {
        val parsed = TitleNormalizer.parse("老友记 第 3 季")
        assertEquals("老友记", parsed.title)
        assertNull(parsed.year)
        assertEquals(3, parsed.season)
        assertNull(parsed.episode)
    }

    @Test
    fun `blank input yields empty parse`() {
        val parsed = TitleNormalizer.parse("   ")
        assertEquals("", parsed.title)
        assertNull(parsed.year)
        assertNull(parsed.season)
        assertNull(parsed.episode)
    }
}
