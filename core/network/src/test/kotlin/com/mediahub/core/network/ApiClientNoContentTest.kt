package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    /** task-22 / A1：收藏取消（DELETE）必须无请求体，且 body==null 时 OkHttp 不报错。 */
    @Test
    fun `deleteNoContent sends DELETE without a request body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val logger = StdoutLogger()
        val api = ApiClient(HttpClientFactory(logger).apiClient(), logger = logger)
        api.deleteNoContent(
            url = server.url("/Users/user-1/FavoriteItems/item-9").toString(),
            headers = mapOf("X-Emby-Token" to "tok"),
        )
        val request = server.takeRequest()
        assertEquals("/Users/user-1/FavoriteItems/item-9", request.path)
        assertEquals("DELETE", request.method)
        assertEquals("tok", request.getHeader("X-Emby-Token"))
        assertEquals(0L, request.bodySize)
    }

    /** 非 2xx 仍走 [ApiException]，method 记为 DELETE（错误映射依赖它）。 */
    @Test
    fun `deleteNoContent throws ApiException carrying DELETE method on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val logger = StdoutLogger()
        val api = ApiClient(HttpClientFactory(logger).apiClient(), logger = logger)
        val error = try {
            api.deleteNoContent(url = server.url("/Users/user-1/FavoriteItems/item-9").toString())
            null
        } catch (e: ApiException) {
            e
        }
        assertNotNull(error)
        assertEquals(404, error!!.statusCode)
        assertEquals("DELETE", error.method)
    }
}
