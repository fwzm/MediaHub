package com.mediahub.metadata.ratings

import com.mediahub.metadata.MetadataMediaType
import com.mediahub.metadata.OkHttpMetadataHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MdbListRatingsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MdbListRatingsSource

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        source = MdbListRatingsSource(
            http = OkHttpMetadataHttpClient(),
            apiKey = TEST_KEY,
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses all six platforms with their native scales`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(RATINGS_JSON),
        )

        val ratings = source.ratings(
            RatingQuery(imdbId = "tt0903747", tmdbId = "1396", mediaType = MetadataMediaType.SERIES),
        )

        assertEquals(6, ratings.size)
        assertRating(ratings, RatingPlatform.IMDB, 9.5, 10, 2_100_000)
        assertRating(ratings, RatingPlatform.TMDB, 8.9, 10, 15_000)
        assertRating(ratings, RatingPlatform.TRAKT, 9.2, 10, 12_000)
        assertRating(ratings, RatingPlatform.LETTERBOXD, 4.3, 5, 800_000)
        assertRating(ratings, RatingPlatform.ROTTEN_TOMATOES, 96.0, 100, null)
        assertRating(ratings, RatingPlatform.METACRITIC, 88.0, 100, null)

        val request = server.takeRequest()
        assertEquals("/imdb/tt0903747", request.requestUrl?.encodedPath)
        assertEquals(TEST_KEY, request.requestUrl?.queryParameter("apikey"))
    }

    @Test
    fun `skips unknown sources and null values`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"ratings":[{"source":"imdb","value":9.0},{"source":"mystery","value":7.0},{"source":"trakt","value":null}]}"""),
        )

        val ratings = source.ratings(RatingQuery(imdbId = "tt0903747"))

        assertEquals(listOf(RatingPlatform.IMDB), ratings.map { it.platform })
    }

    @Test
    fun `missing imdb id performs no request`() = runBlocking {
        val ratings = source.ratings(RatingQuery(imdbId = null, tmdbId = "1396"))

        assertTrue(ratings.isEmpty())
        assertEquals(0, server.requestCount)
    }

    private fun assertRating(
        ratings: List<PlatformRating>,
        platform: RatingPlatform,
        score: Double,
        scale: Int,
        votes: Long?,
    ) {
        val rating = ratings.first { it.platform == platform }
        assertEquals(score, rating.score, 0.001)
        assertEquals(scale, rating.scale)
        assertEquals(votes, rating.votes)
    }

    private companion object {
        const val TEST_KEY = "unit-test-mdblist-key"

        val RATINGS_JSON = """
            {
              "imdbid": "tt0903747",
              "ratings": [
                {"source": "imdb", "value": 9.5, "votes": 2100000},
                {"source": "tmdb", "value": 8.9, "votes": 15000},
                {"source": "trakt", "value": 9.2, "votes": 12000},
                {"source": "letterboxd", "value": 4.3, "votes": 800000},
                {"source": "tomatoes", "value": 96},
                {"source": "metacritic", "value": 88}
              ]
            }
        """.trimIndent()
    }
}
