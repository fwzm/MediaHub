package com.mediahub.core.trakt

import com.mediahub.core.database.repository.TraktWatchlistStore
import com.mediahub.model.TraktResult
import com.mediahub.model.TraktShow
import com.mediahub.model.TraktTrackedShow
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 条目 142/146：Trakt 集成（MockWebServer，绝不访问真实 Trakt）。
 */
class TraktRepositoryTest {

    private lateinit var server: MockWebServer
    private var now = 0L
    private val sleeps = mutableListOf<Long>()
    private lateinit var watchlist: FakeWatchlist

    private class FakeWatchlist : TraktWatchlistStore {
        val items = linkedMapOf<Int, TraktTrackedShow>()
        private val flow = MutableStateFlow<List<TraktTrackedShow>>(emptyList())

        override fun observeTracked(): Flow<List<TraktTrackedShow>> = flow

        override suspend fun allTracked(): List<TraktTrackedShow> = items.values.toList()

        override suspend fun isTracked(traktId: Int): Boolean = items.containsKey(traktId)

        override suspend fun add(show: TraktTrackedShow) {
            items[show.traktId] = show
            flow.value = items.values.toList()
        }

        override suspend fun remove(traktId: Int) {
            items.remove(traktId)
            flow.value = items.values.toList()
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        watchlist = FakeWatchlist()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(
        clientId: String? = "test-client",
        accessToken: String? = "test-token",
        minIntervalMs: Long = 0L,
    ): TraktRepository {
        val config = TraktConfig(
            clientId = clientId,
            accessToken = accessToken,
            baseUrl = server.url("/").toString().trimEnd('/'),
            minRequestIntervalMs = minIntervalMs,
        )
        return TraktRepository(
            config = config,
            watchlist = watchlist,
            rateLimiter = TraktRateLimiter(
                minIntervalMs = minIntervalMs,
                nowMs = { now },
                sleep = { ms ->
                    sleeps += ms
                    now += ms
                },
            ),
            http = TraktHttpClient(config),
            clockMs = { 1_700_000_000_000L },
        )
    }

    @Test
    fun `missing credentials degrade without any network request`() = runBlocking {
        val repo = repository(clientId = null, accessToken = null)

        val result = repo.searchShow("某剧", 2024)

        assertEquals(TraktResult.NotConfigured, result)
        assertEquals(0, server.requestCount)
        assertEquals(
            TraktResult.NotConfigured,
            repo.getCalendar(LocalDate.of(2026, 11, 14)),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `search show sends required headers and parses first match`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [{"score":100.0,"show":{"title":"测试剧集","year":2024,
                  "ids":{"trakt":12345,"slug":"test-show"}}}]
                """.trimIndent(),
            ),
        )
        val repo = repository()

        val result = repo.searchShow("测试剧集", 2024)

        assertTrue(result is TraktResult.Success)
        val show = (result as TraktResult.Success).value
        assertEquals(12345, show.traktId)
        assertEquals("测试剧集", show.title)

        val recorded = server.takeRequest()
        assertEquals("test-client", recorded.getHeader("trakt-api-key"))
        assertEquals("2", recorded.getHeader("trakt-api-version"))
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path!!.startsWith("/search/show"))
        assertTrue(recorded.path!!.contains("query="))
    }

    @Test
    fun `add to watchlist posts ids and updates local list`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        val repo = repository()

        val result = repo.addToWatchlist(TraktShow(traktId = 12345, title = "测试剧集", year = 2024))

        assertTrue(result is TraktResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/sync/watchlist", request.path)
        assertEquals(
            """{"shows":[{"ids":{"trakt":12345}}]}""",
            request.body.readUtf8(),
        )
        assertTrue(watchlist.items.containsKey(12345))
    }

    @Test
    fun `failed add does not touch local watchlist`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val repo = repository()

        val result = repo.addToWatchlist(TraktShow(traktId = 12345, title = "测试剧集", year = 2024))

        assertTrue(result is TraktResult.Failure)
        assertTrue(watchlist.items.isEmpty())
    }

    @Test
    fun `remove from watchlist posts removal and updates local list`() = runBlocking {
        watchlist.add(TraktTrackedShow(12345, "测试剧集", 2024, 1L))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val repo = repository()

        val result = repo.removeFromWatchlist(12345)

        assertTrue(result is TraktResult.Success)
        assertTrue(watchlist.items.isEmpty())
        val request = server.takeRequest()
        assertEquals("/sync/watchlist/remove", request.path)
        assertTrue(request.body.readUtf8().contains("12345"))
    }

    @Test
    fun `429 response surfacing retry after and honoring cooldown`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody("{}"),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"show":{"title":"某剧","year":2024,"ids":{"trakt":9}}}]""",
            ),
        )
        val repo = repository(minIntervalMs = 0L)

        val first = repo.searchShow("某剧", null)

        assertTrue(first is TraktResult.Failure)
        val failure = first as TraktResult.Failure
        assertEquals(60L, failure.retryAfterSeconds)
        assertTrue(failure.message.contains("过于频繁"))

        // 冷却期内再发请求应先等待 60s。
        val second = repo.searchShow("某剧", null)
        assertTrue(second is TraktResult.Success)
        assertEquals(listOf(60_000L), sleeps)
    }

    @Test
    fun `unauthorized response returns actionable message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val repo = repository()

        val result = repo.addToWatchlist(TraktShow(1, "某剧", 2024))

        assertTrue(result is TraktResult.Failure)
        assertTrue((result as TraktResult.Failure).message.contains("授权"))
        assertTrue(watchlist.items.isEmpty())
    }

    @Test
    fun `calendar parses entries with season episode and air date`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [{"first_aired":"2026-11-15T01:00:00.000Z",
                  "episode":{"season":2,"number":5,"title":"第五集"},
                  "show":{"title":"测试剧集","year":2024,"ids":{"trakt":12345}}}]
                """.trimIndent(),
            ),
        )
        val repo = repository()

        val result = repo.getCalendar(LocalDate.of(2026, 11, 14), days = 14)

        assertTrue(result is TraktResult.Success)
        val entry = (result as TraktResult.Success).value.single()
        assertEquals(12345, entry.traktId)
        assertEquals("测试剧集", entry.showTitle)
        assertEquals(2, entry.season)
        assertEquals(5, entry.episode)
        assertEquals("2026-11-15", entry.firstAired)
        assertTrue(server.takeRequest().path!!.startsWith("/calendars/my/shows/2026-11-14/14"))
    }

    @Test
    fun `network failure is reported without crashing`() = runBlocking {
        server.shutdown()
        val repo = repository()

        val result = repo.searchShow("某剧", null)

        assertTrue(result is TraktResult.Failure)
        assertTrue((result as TraktResult.Failure).message.contains("网络异常"))
    }
}
