package com.mediahub.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiExceptionTest {

    @Test
    fun `exception fields message and log string never retain sensitive query values`() {
        val token = "token-plain-secret"
        val encodedSearch = "%E5%86%B0%E8%A1%80%E6%9A%B4"
        val error = ApiException(
            statusCode = 500,
            url = "https://alice:p%40ss@e.example/Items?token=$token&SearchTerm=$encodedSearch&Limit=30",
            method = "GET",
            requestId = "req-1",
        )

        listOf(error.url, error.message.orEmpty(), error.toLogString()).forEach { diagnostic ->
            assertFalse(diagnostic.contains(token))
            assertFalse(diagnostic.contains(encodedSearch, ignoreCase = true))
            assertFalse(diagnostic.contains("冰血暴"))
            assertFalse(diagnostic.contains("alice"))
            assertFalse(diagnostic.contains("p%40ss", ignoreCase = true))
            assertTrue(diagnostic.contains("****"))
            assertTrue(diagnostic.contains("Limit=30"))
        }
    }

    @Test
    fun `custom exception message is redacted before throwable logging`() {
        val error = ApiException(
            statusCode = 500,
            url = "https://e.example/Items",
            method = "GET",
            requestId = "req-2",
            message = "upstream failed: api_key=custom-secret",
        )

        assertFalse(error.message.orEmpty().contains("custom-secret"))
        assertTrue(error.message.orEmpty().contains("api_key=****"))
    }
}
