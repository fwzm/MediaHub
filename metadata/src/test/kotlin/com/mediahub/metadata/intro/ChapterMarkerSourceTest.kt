package com.mediahub.metadata.intro

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterMarkerSourceTest {

    @Test
    fun `maps chapter titles to intro and credits markers`() = runBlocking {
        val source = ChapterMarkerSource(
            listOf(
                MediaChapter("Opening", 0, 90_000),
                MediaChapter("Part One", 90_000, 1_200_000),
                MediaChapter("End Credits", 1_200_000, 1_320_000),
            ),
        )

        val markers = source.markers(MarkerQuery(runtimeMs = null))

        assertEquals(listOf(MarkerType.INTRO, MarkerType.CREDITS), markers.map { it.type })
        assertEquals(0L, markers.first().startMs)
        assertEquals(1_320_000L, markers.last().endMs)
        assertEquals("本地章节", markers.first().source)
    }

    @Test
    fun `recognizes chinese and case variants`() = runBlocking {
        val source = ChapterMarkerSource(
            listOf(
                MediaChapter("片头", 5_000, 65_000),
                MediaChapter("OP", 65_000, 125_000),
                MediaChapter("片尾", 1_300_000, 1_380_000),
            ),
        )

        val markers = source.markers(MarkerQuery())

        assertEquals(listOf(MarkerType.INTRO, MarkerType.INTRO, MarkerType.CREDITS), markers.map { it.type })
    }

    @Test
    fun `ignores unrelated chapters and avoids op substring false positives`() = runBlocking {
        val source = ChapterMarkerSource(
            listOf(
                MediaChapter("Operation Desert", 0, 60_000),
                MediaChapter("Main Story", 60_000, 600_000),
            ),
        )

        assertTrue(source.markers(MarkerQuery()).isEmpty())
    }
}
