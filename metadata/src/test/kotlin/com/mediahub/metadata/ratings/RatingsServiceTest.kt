package com.mediahub.metadata.ratings

import com.mediahub.metadata.MetadataDetail
import com.mediahub.metadata.MetadataException
import com.mediahub.metadata.MetadataMediaType
import com.mediahub.metadata.MetadataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingsServiceTest {

    @Test
    fun `combines tmdb detail rating with external sources`() = runBlocking {
        val service = RatingsService(
            sources = listOf(
                FakeSource("imdb-src", listOf(PlatformRating(RatingPlatform.IMDB, 9.5, 10))),
            ),
        )

        val outcome = service.load(detail(rating = 7.9))

        assertEquals(2, outcome.ratings.ratings.size)
        assertEquals(8.7, outcome.ratings.average!!, 0.001)
        assertTrue(outcome.failures.isEmpty())
    }

    @Test
    fun `failing source is isolated and recorded`() = runBlocking {
        val service = RatingsService(
            sources = listOf(
                FailingSource("broken-src", MetadataException(429, "限流")),
                FakeSource("trakt-src", listOf(PlatformRating(RatingPlatform.TRAKT, 9.0, 10))),
            ),
        )

        val outcome = service.load(detail(rating = 8.0))

        assertEquals(listOf(RatingPlatform.TMDB, RatingPlatform.TRAKT), outcome.ratings.ratings.map { it.platform })
        assertEquals(1, outcome.failures.size)
        assertEquals("broken-src", outcome.failures.single().source)
        assertEquals(429, outcome.failures.single().statusCode)
    }

    @Test
    fun `detail without rating still aggregates external sources`() = runBlocking {
        val service = RatingsService(
            sources = listOf(
                FakeSource("imdb-src", listOf(PlatformRating(RatingPlatform.IMDB, 8.0, 10))),
            ),
        )

        val outcome = service.load(detail(rating = null))

        assertEquals(listOf(RatingPlatform.IMDB), outcome.ratings.ratings.map { it.platform })
        assertEquals(8.0, outcome.ratings.average!!, 0.001)
    }

    private class FakeSource(
        override val name: String,
        private val ratings: List<PlatformRating>,
    ) : RatingsSource {
        override suspend fun ratings(query: RatingQuery): List<PlatformRating> = ratings
    }

    private class FailingSource(
        override val name: String,
        private val error: Exception,
    ) : RatingsSource {
        override suspend fun ratings(query: RatingQuery): List<PlatformRating> = throw error
    }

    private fun detail(rating: Double?) = MetadataDetail(
        source = MetadataSource.TMDB,
        id = "tv/1396",
        title = "绝命毒师",
        rating = rating,
        mediaType = MetadataMediaType.SERIES,
        externalIds = mapOf("imdb" to "tt0903747", "tmdb" to "1396"),
    )
}
