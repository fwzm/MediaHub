package com.mediahub.core.danmaku

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DanDanPlayClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DanDanPlayClient

    private val config = DanDanPlayConfig(
        baseUrl = "",
        appId = "test-app",
        appSecret = "test-secret",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DanDanPlayClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun configured(): DanDanPlayConfig = config.copy(baseUrl = server.url("/").toString())

    @Test
    fun `search sends credentials and parses episodes`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"success":true,"episodes":[
                  {"episodeId":101,"animeTitle":"Frieren","episodeTitle":"第1话","episodeNumber":1},
                  {"episodeId":102,"animeTitle":"Frieren","episodeTitle":"第2话","episodeNumber":2}
                ]}
                """.trimIndent(),
            ),
        )

        val episodes = client.searchEpisodes(configured(), "Frieren")

        assertEquals(2, episodes.size)
        assertEquals("101", episodes[0].id)
        assertEquals("第1话", episodes[0].episodeTitle)
        assertEquals(1, episodes[0].episodeNumber)

        val request = server.takeRequest()
        assertEquals("test-app", request.getHeader("X-AppId"))
        assertEquals("test-secret", request.getHeader("X-AppSecret"))
        assertTrue(request.path!!.startsWith("/api/v2/search/episodes"))
        assertTrue(request.path!!.contains("anime=Frieren"))
    }

    @Test
    fun `fetch comments parses payload`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"count":1,"comments":[{"cid":7,"p":"1.5,1,16777215","m":"hi"}]}""",
            ),
        )

        val comments = client.fetchComments(configured(), "101")

        assertEquals(1, comments.size)
        assertEquals(1500L, comments[0].timeMs)
        assertEquals("hi", comments[0].text)
        assertTrue(server.takeRequest().path!!.startsWith("/api/v2/comment/101"))
    }

    @Test
    fun `http error throws source exception`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false}"""))

        assertThrows(DanmakuSourceException::class.java) {
            kotlinx.coroutines.runBlocking { client.searchEpisodes(configured(), "any") }
        }
    }

    @Test
    fun `malformed body does not throw and yields empty`() = runTest {
        server.enqueue(MockResponse().setBody("not-json"))

        assertTrue(client.fetchComments(configured(), "101").isEmpty())
    }
}
