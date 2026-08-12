package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ApiClientNoContentTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() { server = MockWebServer().apply { start() } }
    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `postNoContent sends request and ignores empty body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val logger = StdoutLogger()
        val api = ApiClient(HttpClientFactory(logger).apiClient(), logger = logger)
        api.postNoContent(
            url = server.url("/Sessions/Logout").toString(),
            headers = mapOf("X-Emby-Token" to "tok"),
        )
        val request = server.takeRequest()
        assertEquals("/Sessions/Logout", request.path)
        assertEquals("POST", request.method)
        assertEquals("tok", request.getHeader("X-Emby-Token"))
    }
}
