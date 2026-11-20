package com.mediahub.player.mpv

import android.net.Uri
import com.mediahub.core.logging.StdoutLogger
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 审计条目 66（部分字幕乱码）——落地转码契约回归。
 *
 * 契约：字幕缓存落盘的文件**一律是 UTF-8**。GBK/GB18030/Big5 等中文编码的字幕
 * 必须在发布（rename 到目标文件）之前按探测结果解码并重写为 UTF-8；
 * 已经是 UTF-8 的内容必须逐字节不变（不得无谓改写用户的合法文件）。
 *
 * 现状缺陷：download()/ContentCopy 把响应字节原样写盘，mpv 按 UTF-8 渲染 → 用户看到乱码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class A66SubtitleCharsetTranscodeTest {

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

    private fun cache(copy: SubtitleCache.ContentCopy = SubtitleCache.ContentCopy { _, _ -> false }) =
        SubtitleCache(
            cacheDir = tmp.newFolder(),
            client = OkHttpClient(),
            contentResolver = copy,
            logger = StdoutLogger(),
        )

    private val simplified = "你好，这是字幕测试。第一行，第二行。"
    private val traditional = "繁體中文字幕測試，謝謝觀看。"

    private fun encode(text: String, charset: String): ByteArray = text.toByteArray(Charset.forName(charset))

    @Test
    fun `gb18030 subtitle downloaded over http is stored as utf8`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(encode(simplified, "GB18030"))))
        val url = server.url("/movie.zh.srt").toString()

        val path = cache().localPathFor(url, "https://media.example/stream.mkv", "srv/movie", emptyMap())
        assertNotNull("契约：字幕必须落地成功", path)
        val stored = File(path!!).readBytes()
        assertEquals(simplified, String(stored, Charsets.UTF_8))
    }

    @Test
    fun `utf8 subtitle is stored byte identical`() = runBlocking {
        val original = encode(simplified, "UTF-8")
        server.enqueue(MockResponse().setBody(okio.Buffer().write(original)))
        val url = server.url("/movie.zh.srt").toString()

        val path = cache().localPathFor(url, "https://media.example/stream.mkv", "srv/movie", emptyMap())
        assertNotNull(path)
        assertEquals(original.toList(), File(path!!).readBytes().toList())
    }

    @Test
    fun `big5 subtitle imported from saf is stored as utf8`() = runBlocking {
        val bytes = encode(traditional, "Big5")
        val copy = SubtitleCache.ContentCopy { _, target ->
            target.outputStream().use { it.write(bytes) }
            true
        }

        val path = cache(copy).localPathFor(
            "content://imported/movie.zh.srt",
            "https://media.example/stream.mkv",
            "srv/movie",
            emptyMap(),
        )
        assertNotNull(path)
        assertEquals(traditional, String(File(path!!).readBytes(), Charsets.UTF_8))
    }
}
