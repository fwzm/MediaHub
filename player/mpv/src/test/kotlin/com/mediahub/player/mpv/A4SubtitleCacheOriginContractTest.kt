package com.mediahub.player.mpv

import com.mediahub.core.logging.StdoutLogger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A4 轮字幕缓存 origin 契约回归（正式回归，长期保留）。
 *
 * 证据对应：归档件 mh-a-round/a4-evidence/repro-sources/BSubtitleOriginContractTest.kt.txt
 * （Agent B 复现的 3 条断言在此**转正**），并做双向扩展：
 * - 隐式/显式 × 80/443/5005 组合矩阵（反射调用生产 [SubtitleCache] 的 originOf）；
 * - 真实请求凭据作用域（MockWebServer）。
 *
 * 契约（对齐 provider/webdav WebDavModel.origin 的 effectivePort 语义）：
 * origin 按 scheme 默认端口归一（http→80、https→443；未显式端口的 -1 归一到默认），
 * 因此"HTTP 隐式 ↔ 显式 :80"、"HTTPS 隐式 ↔ 显式 :443"必须判**同源**（同源媒体凭据可携带）；
 * 非默认端口（如 :5005）、跨 scheme、跨 host 必须判**异源**（凭据绝不发往）。
 *
 * 真实请求说明：隐式端口（80/443）的真实 HTTP 请求需 MockWebServer 绑定特权端口，
 * 单测环境不可行——该等价关系由反射级矩阵断言覆盖；真实请求凭据断言用显式端口
 * 覆盖"同源（显式==显式）携带 / 异源（默认端口 ↔ :5005）不携带"。
 */
class A4SubtitleCacheOriginContractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val cache = SubtitleCache(
        cacheDir = java.io.File("build/a4-tmp-origin-cache"),
        client = OkHttpClient(),
        contentResolver = { _, _ -> true },
        logger = StdoutLogger(),
    )

    /** 反射调用生产 private originOf（无默认参数，JVM 名不修饰）——判定函数本身是被测对象。 */
    private fun originOf(url: String): String? {
        val method = cache.javaClass.getDeclaredMethod("originOf", String::class.java)
        method.isAccessible = true
        return method.invoke(cache, url) as String?
    }

    // ---- B 转正三断言（归档件 BSubtitleOriginContractTest 原文语义） ----

    @Test
    fun `implicit 80 and explicit 80 are same origin`() {
        val implicitHttp = originOf("http://nas.example/dav/video.mkv")
        val explicit80 = originOf("http://nas.example:80/dav/video.mkv")
        assertEquals("契约：HTTP 隐式端口与显式 :80 同源", implicitHttp, explicit80)
    }

    @Test
    fun `implicit 443 and explicit 443 are same origin`() {
        val implicitHttps = originOf("https://nas.example/dav/video.mkv")
        val explicit443 = originOf("https://nas.example:443/dav/video.mkv")
        assertEquals("契约：HTTPS 隐式端口与显式 :443 同源", implicitHttps, explicit443)
    }

    @Test
    fun `default port and 5005 must stay different origins`() {
        val implicitHttp = originOf("http://nas.example/dav/video.mkv")
        val port5005 = originOf("http://nas.example:5005/dav/video.mkv")
        assertNotEquals("反向锁定：默认端口与 :5005 异源", implicitHttp, port5005)
    }

    // ---- 双向扩展矩阵：隐式/显式 × 80/443/5005 ----

    @Test
    fun `matrix implicit and explicit default ports are equivalent both directions`() {
        // 双向：隐式→显式 与 显式→隐式 判定一致（等值关系，非单向包含）
        assertEquals(originOf("http://h.example/a.srt"), originOf("http://h.example:80/a.srt"))
        assertEquals(originOf("http://h.example:80/a.srt"), originOf("http://h.example/a.srt"))
        assertEquals(originOf("https://h.example/a.srt"), originOf("https://h.example:443/a.srt"))
        assertEquals(originOf("https://h.example:443/a.srt"), originOf("https://h.example/a.srt"))
    }

    @Test
    fun `matrix non-default ports never equal implicit defaults`() {
        // :5005 与 http/https 隐式端口均异源
        assertNotEquals(originOf("http://h.example/a.srt"), originOf("http://h.example:5005/a.srt"))
        assertNotEquals(originOf("https://h.example/a.srt"), originOf("https://h.example:5005/a.srt"))
        // scheme 的"错误默认端口"（http:443 / https:80）同样异源——端口按 scheme 归一，不按数值
        assertNotEquals(originOf("http://h.example/a.srt"), originOf("http://h.example:443/a.srt"))
        assertNotEquals(originOf("https://h.example/a.srt"), originOf("https://h.example:80/a.srt"))
    }

    @Test
    fun `matrix scheme host and port each alone change origin`() {
        // 同 host+port 跨 scheme：异源
        assertNotEquals(originOf("http://h.example/a.srt"), originOf("https://h.example/a.srt"))
        // 同 scheme 不同 host：异源（含大小写不同的 host 判同源的反向锁定在下一断言）
        assertNotEquals(originOf("http://a.example/a.srt"), originOf("http://b.example/a.srt"))
        // host 大小写不敏感：同源
        assertEquals(originOf("http://NAS.Example/a.srt"), originOf("http://nas.example/a.srt"))
        // 显式默认端口与另一显式默认端口（等值）：同源
        assertEquals(originOf("http://h.example:80/a.srt"), originOf("http://h.example:80/b.srt"))
    }

    // ---- 真实请求凭据作用域（显式端口；隐式端口 80/443 绑定不可行，见类注释） ----

    @Test
    fun `same origin explicit ports real request carries authorization`() = runBlocking {
        server.enqueue(MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nhi\n"))
        val subject = SubtitleCache(
            cacheDir = tmp.newFolder(),
            client = OkHttpClient(),
            contentResolver = { _, _ -> false },
            logger = StdoutLogger(),
        )
        val mediaUrl = server.url("/dav/movie.mkv").toString()
        val subtitleUrl = server.url("/dav/movie.vtt").toString()

        val path = subject.localPathFor(
            uri = subtitleUrl,
            mediaUrl = mediaUrl,
            scopeKey = "a4-origin-test",
            sessionHeaders = mapOf("Authorization" to "Basic dXNlcjpwYXNz"),
        )

        assertNotNull("同源（显式==显式）落地必须成功", path)
        assertEquals(
            "同 origin（字幕与媒体同为显式端口同 host）必须携带会话凭据",
            "Basic dXNlcjpwYXNz",
            server.takeRequest().getHeader("Authorization"),
        )
    }

    @Test
    fun `cross origin port 5005 real request never carries credentials`() = runBlocking {
        // 媒体 URL 指向同 host 但 :5005（异 origin）：凭据绝不发往
        server.enqueue(MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nhi\n"))
        val subject = SubtitleCache(
            cacheDir = tmp.newFolder(),
            client = OkHttpClient(),
            contentResolver = { _, _ -> false },
            logger = StdoutLogger(),
        )
        val port = server.port
        val subtitleUrl = server.url("/dav/movie.vtt").toString()
        val mediaUrl = subtitleUrl.replace(":$port/", ":5005/")

        val path = subject.localPathFor(
            uri = subtitleUrl,
            mediaUrl = mediaUrl,
            scopeKey = "a4-origin-test",
            sessionHeaders = mapOf("Authorization" to "Basic dXNlcjpwYXNz"),
        )

        assertNotNull("异源匿名下载本身应成功（本用例响应 200）", path)
        assertNull(
            "默认端口 ↔ :5005 异源：凭据绝不携带",
            server.takeRequest().getHeader("Authorization"),
        )
    }
}
