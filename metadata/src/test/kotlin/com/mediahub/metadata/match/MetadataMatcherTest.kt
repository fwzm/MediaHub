package com.mediahub.metadata.match

import com.mediahub.metadata.MetadataMediaType
import com.mediahub.metadata.MetadataResult
import com.mediahub.metadata.MetadataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataMatcherTest {

    private val matcher = MetadataMatcher()

    @Test
    fun `exact title and year ranks first with confidence`() {
        val ranked = matcher.rank(
            query = "绝命毒师 2008",
            candidates = listOf(
                series("tv/2", "绝命毒师", 2008),
                series("tv/1", "绝命毒师", 2019),
                series("tv/3", "绝命毒师：续命之徒", 2019),
            ),
        )

        assertEquals("tv/2", ranked.first().result.id)
        assertTrue(ranked.first().confident)
        assertTrue(ranked.first().reasons.contains(MatchReason.TITLE_EXACT))
        assertTrue(ranked.first().reasons.contains(MatchReason.YEAR_EXACT))
    }

    @Test
    fun `original title matches when display title is localized`() {
        val ranked = matcher.rank(
            query = "Breaking Bad",
            candidates = listOf(
                series("tv/1", "绝命毒师", 2008, originalTitle = "Breaking Bad", language = "en"),
            ),
        )

        assertEquals("tv/1", ranked.single().result.id)
        assertTrue(ranked.single().reasons.contains(MatchReason.ORIGINAL_TITLE_EXACT))
        assertTrue(ranked.single().confident)
    }

    @Test
    fun `partial title is ranked below exact title`() {
        val ranked = matcher.rank(
            query = "绝命毒师",
            candidates = listOf(
                series("tv/partial", "绝命毒师：续命之徒", 2019),
                series("tv/exact", "绝命毒师", 2008),
            ),
        )

        assertEquals(listOf("tv/exact", "tv/partial"), ranked.map { it.result.id })
    }

    @Test
    fun `year mismatch demotes same title`() {
        val ranked = matcher.rank(
            query = "绝命毒师",
            year = 2008,
            candidates = listOf(
                series("tv/old", "绝命毒师", 1999),
                series("tv/new", "绝命毒师", 2008),
            ),
        )

        assertEquals("tv/new", ranked.first().result.id)
        assertTrue(ranked.first { it.result.id == "tv/old" }.reasons.contains(MatchReason.YEAR_MISMATCH))
    }

    @Test
    fun `preferred language breaks tie`() {
        val ranked = matcher.rank(
            query = "Breaking Bad",
            candidates = listOf(
                series("tv/en", "Breaking Bad", 2008, language = "en"),
                series("tv/zh", "Breaking Bad", 2008, language = "zh"),
            ),
            preferredLanguage = "zh",
        )

        assertEquals("tv/zh", ranked.first().result.id)
        assertTrue(ranked.first().reasons.contains(MatchReason.LANGUAGE_MATCH))
    }

    @Test
    fun `unrelated candidates are dropped`() {
        val ranked = matcher.rank(
            query = "绝命毒师",
            candidates = listOf(
                series("tv/1", "绝命毒师", 2008),
                series("tv/2", "老友记", 1994),
            ),
        )

        assertEquals(listOf("tv/1"), ranked.map { it.result.id })
    }

    @Test
    fun `equal scores keep deterministic title order`() {
        val ranked = matcher.rank(
            query = "Breaking Bad",
            candidates = listOf(
                series("tv/b", "Breaking Bad", 2008, language = "en"),
                series("tv/a", "Breaking Bad", 2008, language = "en"),
            ),
        )

        assertEquals(listOf("tv/a", "tv/b"), ranked.map { it.result.id })
    }

    @Test
    fun `blank query returns no candidates`() {
        assertTrue(matcher.rank("   ", listOf(series("tv/1", "绝命毒师", 2008))).isEmpty())
    }

    @Test
    fun `explicit year overrides year embedded in query`() {
        val ranked = matcher.rank(
            query = "绝命毒师 2019",
            year = 2008,
            candidates = listOf(series("tv/1", "绝命毒师", 2008)),
        )

        assertFalse(ranked.single().reasons.contains(MatchReason.YEAR_MISMATCH))
    }

    private fun series(
        id: String,
        title: String,
        year: Int?,
        originalTitle: String? = null,
        language: String? = null,
    ) = MetadataResult(
        source = MetadataSource.TMDB,
        id = id,
        title = title,
        year = year,
        originalTitle = originalTitle,
        language = language,
        mediaType = MetadataMediaType.SERIES,
    )
}
