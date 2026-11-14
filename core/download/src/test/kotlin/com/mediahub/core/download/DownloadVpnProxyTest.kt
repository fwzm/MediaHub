package com.mediahub.core.download

import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 条目 68：VPN / 代理环境下下载不得依赖“直连可用性”判断。
 *
 * 用一个**无法直连解析**的主机名 + HTTP 代理验证：下载是否可达完全由 HTTP 栈决定，
 * 内核不做 DNS/直连预检、不发 HEAD 探测，VPN-only 场景照常下载。
 */
class DownloadVpnProxyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var proxy: MockWebServer
    private lateinit var root: File

    @Before
    fun setUp() {
        proxy = MockWebServer()
        proxy.start()
        root = temp.newFolder("downloads")
    }

    @After
    fun tearDown() {
        proxy.shutdown()
    }

    @Test
    fun `downloads through proxy even when origin host is not directly resolvable`() = runBlocking {
        proxy.enqueue(MockResponse().setBody("via-vpn"))
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
            .build()
        val manager = DownloadManager(root, client)

        val record = manager.download(
            DownloadRequest(
                serverId = "s1",
                itemId = "i1",
                // .invalid 不会直连解析成功：只有代理（VPN 暴露的出口）能取到内容。
                url = "http://vpn-only.invalid/cloud/movie.mp4",
                fileName = "movie.mp4",
            ),
        )

        assertEquals(DownloadStatus.COMPLETED, record.status)
        assertEquals("via-vpn", manager.expectedFile("s1", "i1", "movie.mp4").readText())
        // 没有 HEAD 预检 / 没有额外探测请求：一次 GET 直达。
        assertEquals(1, proxy.requestCount)
        val seen = proxy.takeRequest()
        // 代理收到的是 absolute-form 请求行，且目标主机不是代理自身。
        assertEquals("GET http://vpn-only.invalid/cloud/movie.mp4 HTTP/1.1", seen.requestLine)
        assertEquals("vpn-only.invalid", seen.headers["Host"])
        assertEquals("GET", seen.method)
    }

    @Test
    fun `resume range request also goes through proxy`() = runBlocking {
        proxy.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/10")
                .setBody("456789"),
        )
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
            .build()
        val manager = DownloadManager(root, client)
        manager.partialFile("s1", "i2", "movie.mp4").apply {
            parentFile.mkdirs()
            writeBytes("0123".toByteArray())
        }

        val record = manager.download(
            DownloadRequest(
                serverId = "s1",
                itemId = "i2",
                url = "http://vpn-only.invalid/cloud/movie.mp4",
                fileName = "movie.mp4",
            ),
        )

        assertEquals(DownloadStatus.COMPLETED, record.status)
        assertEquals("bytes=4-", proxy.takeRequest().getHeader("Range"))
        assertEquals("0123456789", manager.expectedFile("s1", "i2", "movie.mp4").readText())
    }

    @Test
    fun `proxy authentication failure is reported as auth required not direct network error`() = runBlocking {
        proxy.enqueue(MockResponse().setResponseCode(407))
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
            .build()
        val manager = DownloadManager(root, client)

        val record = manager.download(
            DownloadRequest(
                serverId = "s1",
                itemId = "i3",
                url = "http://vpn-only.invalid/cloud/movie.mp4",
                fileName = "movie.mp4",
            ),
        )

        assertEquals(DownloadStatus.FAILED, record.status)
        assertEquals(DownloadFailureKind.HTTP, record.failureKind)
        assertEquals("HTTP 407", record.message)
        assertTrue(record.message?.contains("407") == true)
    }
}
