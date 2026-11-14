package com.mediahub.metadata.tmdb

import com.mediahub.metadata.DefaultMetadataRegistry
import com.mediahub.metadata.MetadataException
import com.mediahub.metadata.MetadataSource
import com.mediahub.metadata.OkHttpMetadataHttpClient
import com.mediahub.model.Person
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TmdbMetadataProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: TmdbMetadataProvider

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val api = TmdbApi(
            http = OkHttpMetadataHttpClient(),
            apiKey = TEST_API_KEY,
            baseUrl = server.url("/3").toString(),
        )
        provider = TmdbMetadataProvider(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search maps tv and movie results and sends api key plus query`() = runBlocking {
        server.enqueue(jsonResponse(SEARCH_JSON))

        val results = provider.search("绝命毒师")

        assertEquals(2, results.size)
        val tv = results.first { it.id == "tv/1396" }
        assertEquals(MetadataSource.TMDB, tv.source)
        assertEquals("绝命毒师", tv.title)
        assertEquals(2008, tv.year)
        assertEquals("Breaking Bad", tv.originalTitle)
        assertEquals("en", tv.language)
        assertEquals(com.mediahub.metadata.MetadataMediaType.SERIES, tv.mediaType)
        assertEquals(POSTER_PREFIX + "ggFHVNu6YYI5L9pCfOacjizRGt.jpg", tv.posterUrl)
        assertTrue(tv.overview!!.startsWith("中学化学老师"))

        val movie = results.first { it.id == "movie/550" }
        assertEquals("搏击俱乐部", movie.title)
        assertEquals(1999, movie.year)

        val request = server.takeRequest()
        assertEquals("/3/search/multi", request.requestUrl?.encodedPath)
        assertEquals("绝命毒师", request.requestUrl?.queryParameter("query"))
        assertEquals(TEST_API_KEY, request.requestUrl?.queryParameter("api_key"))
        assertEquals("zh-CN", request.requestUrl?.queryParameter("language"))
        assertEquals("false", request.requestUrl?.queryParameter("include_adult"))
    }

    @Test
    fun `search drops person results and filters by year`() = runBlocking {
        server.enqueue(jsonResponse(SEARCH_JSON))

        val results = provider.search("breaking", year = 2008)

        assertEquals(listOf("tv/1396"), results.map { it.id })
    }

    @Test
    fun `detail maps tv genres people rating and external ids`() = runBlocking {
        server.enqueue(jsonResponse(TV_DETAIL_JSON))

        val detail = checkNotNull(provider.detail("tv/1396"))

        assertEquals(MetadataSource.TMDB, detail.source)
        assertEquals("tv/1396", detail.id)
        assertEquals("绝命毒师", detail.title)
        assertEquals(2008, detail.year)
        assertEquals(8.9, detail.rating!!, 0.0001)
        assertEquals(listOf("剧情", "犯罪"), detail.genres)
        assertEquals(POSTER_PREFIX + "ggFHVNu6YYI5L9pCfOacjizRGt.jpg", detail.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w780/backdrop.jpg", detail.backdropUrl)
        assertEquals(com.mediahub.metadata.MetadataMediaType.SERIES, detail.mediaType)
        assertEquals("tt0903747", detail.externalIds["imdb"])
        assertEquals("81189", detail.externalIds["tvdb"])
        assertEquals("1396", detail.externalIds["tmdb"])

        val actor = detail.people.first { it.name == "布莱恩·科兰斯顿" }
        assertEquals(Person.Role.ACTOR, actor.role)
        assertEquals("Walter White", actor.characterName)
        assertEquals("17419", actor.id)

        val director = detail.people.first { it.name == "文斯·吉里根" }
        assertEquals(Person.Role.DIRECTOR, director.role)

        val request = server.takeRequest()
        assertEquals("/3/tv/1396", request.requestUrl?.encodedPath)
        assertEquals("credits,external_ids", request.requestUrl?.queryParameter("append_to_response"))
    }

    @Test
    fun `detail returns null when tmdb answers 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status_code":34}"""))

        assertNull(provider.detail("movie/999999999"))
    }

    @Test
    fun `detail rejects id without media type prefix`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { provider.detail("1396") }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `seasons include specials and not yet aired seasons`() = runBlocking {
        server.enqueue(jsonResponse(TV_DETAIL_JSON))

        val seasons = provider.seasons("tv/1396")

        assertEquals(listOf(0, 1, 6), seasons.map { it.seasonNumber })
        val specials = seasons.first { it.seasonNumber == 0 }
        assertEquals("特别篇", specials.title)
        assertEquals(5, specials.episodeCount)
        val future = seasons.first { it.seasonNumber == 6 }
        assertEquals("第 6 季", future.title)
        assertEquals("2027-01-01", future.airDate)

        val request = server.takeRequest()
        assertEquals("/3/tv/1396", request.requestUrl?.encodedPath)
    }

    @Test
    fun `episodes expose air date and runtime even before library import`() = runBlocking {
        server.enqueue(jsonResponse(SEASON_DETAIL_JSON))

        val episodes = provider.episodes("tv/1396", 1)

        assertEquals(2, episodes.size)
        val pilot = episodes.first { it.episodeNumber == 1 }
        assertEquals(1, pilot.seasonNumber)
        assertEquals("试播集", pilot.title)
        assertEquals("2008-01-20", pilot.airDate)
        assertEquals(58, pilot.runtimeMinutes)
        assertEquals("https://image.tmdb.org/t/p/w300/still1.jpg", pilot.stillUrl)

        val unaired = episodes.first { it.episodeNumber == 2 }
        assertEquals("2027-02-01", unaired.airDate)
        assertNull(unaired.runtimeMinutes)

        val request = server.takeRequest()
        assertEquals("/3/tv/1396/season/1", request.requestUrl?.encodedPath)
    }

    @Test
    fun `http error becomes metadata exception without leaking api key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status_message":"invalid api key"}"""))

        val error = assertThrows(MetadataException::class.java) {
            runBlocking { provider.detail("tv/1396") }
        }

        assertEquals(401, error.statusCode)
        assertFalse(error.message.orEmpty().contains(TEST_API_KEY))
    }

    @Test
    fun `registry resolves tmdb provider and returns null for unregistered source`() {
        val registry = DefaultMetadataRegistry(listOf(provider))

        assertEquals(provider, registry.providerFor(MetadataSource.TMDB))
        assertNull(registry.providerFor(MetadataSource.BANGUMI))
    }

    private companion object {
        const val TEST_API_KEY = "unit-test-api-key"
        const val POSTER_PREFIX = "https://image.tmdb.org/t/p/w500/"

        fun jsonResponse(body: String): MockResponse =
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

        val SEARCH_JSON = """
            {
              "page": 1,
              "results": [
                {
                  "id": 1396,
                  "media_type": "tv",
                  "name": "绝命毒师",
                  "original_name": "Breaking Bad",
                  "first_air_date": "2008-01-20",
                  "overview": "中学化学老师得知自己身患绝症后开始制毒。",
                  "poster_path": "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg",
                  "original_language": "en"
                },
                {
                  "id": 550,
                  "media_type": "movie",
                  "title": "搏击俱乐部",
                  "original_title": "Fight Club",
                  "release_date": "1999-10-15",
                  "overview": "一个失眠的白领遇见肥皂商人。",
                  "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg"
                },
                {
                  "id": 287,
                  "media_type": "person",
                  "name": "布拉德·皮特"
                }
              ],
              "total_pages": 1,
              "total_results": 3
            }
        """.trimIndent()

        val TV_DETAIL_JSON = """
            {
              "id": 1396,
              "name": "绝命毒师",
              "original_name": "Breaking Bad",
              "overview": "中学化学老师得知自己身患绝症后开始制毒。",
              "first_air_date": "2008-01-20",
              "genres": [
                {"id": 18, "name": "剧情"},
                {"id": 80, "name": "犯罪"}
              ],
              "vote_average": 8.9,
              "poster_path": "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg",
              "backdrop_path": "/backdrop.jpg",
              "credits": {
                "cast": [
                  {"id": 17419, "name": "布莱恩·科兰斯顿", "character": "Walter White"}
                ],
                "crew": [
                  {"id": 66633, "name": "文斯·吉里根", "job": "Director"},
                  {"id": 66634, "name": "皮特·古尔德", "job": "Writer"}
                ]
              },
              "external_ids": {
                "imdb_id": "tt0903747",
                "tvdb_id": 81189
              },
              "seasons": [
                {"id": 3572, "season_number": 1, "name": "第 1 季", "episode_count": 7, "air_date": "2008-01-20", "overview": "第一季", "poster_path": "/s1.jpg"},
                {"id": 3571, "season_number": 0, "name": "特别篇", "episode_count": 5, "air_date": "2009-02-17", "overview": "幕后", "poster_path": null},
                {"id": 9999, "season_number": 6, "name": "第 6 季", "episode_count": 10, "air_date": "2027-01-01", "overview": "未播出", "poster_path": "/s6.jpg"}
              ]
            }
        """.trimIndent()

        val SEASON_DETAIL_JSON = """
            {
              "id": 3572,
              "season_number": 1,
              "name": "第 1 季",
              "overview": "第一季",
              "poster_path": "/s1.jpg",
              "air_date": "2008-01-20",
              "episodes": [
                {
                  "id": 62085,
                  "episode_number": 1,
                  "name": "试播集",
                  "overview": "一切开始的地方。",
                  "still_path": "/still1.jpg",
                  "air_date": "2008-01-20",
                  "runtime": 58
                },
                {
                  "id": 62086,
                  "episode_number": 2,
                  "name": "猫入袋中",
                  "overview": "尚未播出。",
                  "still_path": "/still2.jpg",
                  "air_date": "2027-02-01",
                  "runtime": null
                }
              ]
            }
        """.trimIndent()
    }
}
