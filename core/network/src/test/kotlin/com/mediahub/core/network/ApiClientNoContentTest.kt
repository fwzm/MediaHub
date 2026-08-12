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

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `postNoContent sends an empty post body and ignores an empty response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val logger = StdoutLogger()
        ApiClient(HttpClientFactory(logger).apiClient(), logger = logger).postNoContent(
            url = server.url("/Sessions/Logout").toString(),
            headers = mapOf("X-Emby-Token" to "token"),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/Sessions/Logout", request.path)
        assertEquals("token", request.getHeader("X-Emby-Token"))
    }
}
