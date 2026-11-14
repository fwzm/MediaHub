package com.mediahub.metadata.ratings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingsAggregatorTest {

    private val aggregator = RatingsAggregator()

    @Test
    fun `normalizes each platform to ten point scale`() {
        val result = aggregator.aggregate(
            listOf(
                listOf(
                    PlatformRating(RatingPlatform.IMDB, 9.5, 10),
                    PlatformRating(RatingPlatform.ROTTEN_TOMATOES, 96.0, 100),
                    PlatformRating(RatingPlatform.LETTERBOXD, 4.3, 5),
                    PlatformRating(RatingPlatform.METACRITIC, 88.0, 100),
                ),
            ),
        )

        assertEquals(9.5, result.byPlatform(RatingPlatform.IMDB)!!.normalizedScore, 0.001)
        assertEquals(9.6, result.byPlatform(RatingPlatform.ROTTEN_TOMATOES)!!.normalizedScore, 0.001)
        assertEquals(8.6, result.byPlatform(RatingPlatform.LETTERBOXD)!!.normalizedScore, 0.001)
        assertEquals(8.8, result.byPlatform(RatingPlatform.METACRITIC)!!.normalizedScore, 0.001)
        assertEquals(9.125, result.average!!, 0.001)
    }

    @Test
    fun `dedupes platforms keeping first source priority`() {
        val result = aggregator.aggregate(
            listOf(
                listOf(PlatformRating(RatingPlatform.TRAKT, 8.0, 10)),
                listOf(PlatformRating(RatingPlatform.TRAKT, 9.0, 10)),
            ),
        )

        assertEquals(1, result.ratings.size)
        assertEquals(8.0, result.ratings.single().score, 0.001)
    }

    @Test
    fun `empty input yields empty aggregate`() {
        val result = aggregator.aggregate(emptyList())

        assertTrue(result.ratings.isEmpty())
        assertNull(result.average)
    }

    @Test
    fun `display text follows platform conventions`() {
        assertEquals("96%", PlatformRating(RatingPlatform.ROTTEN_TOMATOES, 96.0, 100).display)
        assertEquals("88/100", PlatformRating(RatingPlatform.METACRITIC, 88.0, 100).display)
        assertEquals("4.3/5", PlatformRating(RatingPlatform.LETTERBOXD, 4.3, 5).display)
        assertEquals("9.5/10", PlatformRating(RatingPlatform.IMDB, 9.5, 10).display)
    }

    @Test
    fun `score is clamped into platform range`() {
        val rating = PlatformRating(RatingPlatform.IMDB, 12.0, 10)
        assertEquals(10.0, rating.normalizedScore, 0.001)
    }
}
