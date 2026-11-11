package com.mediahub.core.network

import com.mediahub.core.logging.LogBuffer
import com.mediahub.core.logging.MemoryLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientSecurityTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `error response redacts quoted secret before log length limit`() = runBlocking {
        val longSecret = "secret-start " + "x".repeat(600) + " secret-end"
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("{\"password\":\"$longSecret\",\"error\":\"denied\"}")
        )
        val buffer = LogBuffer()
        val logger = MemoryLogger(buffer)
        val api = ApiClient(HttpClientFactory(logger).apiClient(), logger = logger)

        try {
            api.get<JsonObject>(server.url("/failure").toString())
            fail("non-2xx response must throw ApiException")
        } catch (_: ApiException) {
            // Expected.
        }

        val line = buffer.snapshot().single { it.contains("API 500") }
        assertFalse(line.contains("secret-start"))
        assertFalse(line.contains("secret-end"))
        assertFalse(line.contains("x".repeat(40)))
        assertTrue(line.contains("\"password\":\"****\""))
        assertTrue(line.contains("\"error\":\"denied\""))
    }
}
