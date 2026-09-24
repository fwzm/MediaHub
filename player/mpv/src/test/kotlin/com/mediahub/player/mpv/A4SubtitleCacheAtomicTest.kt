package com.mediahub.player.mpv

import android.net.Uri
import com.mediahub.core.logging.StdoutLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A4 轮字幕缓存原子落地回归（content:// SAF 路径 + http 读取上限）。
 *
 * 证据对应：归档件 mh-a-round/a4-evidence/repro-sources/BSubtitleSafPartialReuseTest.kt.txt
 * （Agent B 复现"content:// 半成品复用"；其 XML 在 a4-evidence/test-results/，
 * 是旧 head 缺陷证据而非新候选验收）。本类断言**正确行为**（B 件的镜像反转）：
 * - 首次拷贝中途失败：无正式文件残留，.part 被清理；
 * - 再次请求：真实重拷（不复用半成品），最终完整内容；
 * - http 路径本就有 .part+rename，本类锁定 content:// 对齐该语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class A4SubtitleCacheAtomicTest {

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

    private var cacheRoot: File? = null

    private fun subtitlesDir(): File = File(requireNotNull(cacheRoot), "subtitles")

    @Test
    fun `interrupted saf copy leaves no residue and next request re-copies fully`() = runBlocking {
        cacheRoot = tmp.newFolder()
        var calls = 0
        val subject = SubtitleCache(
            cacheDir = requireNotNull(cacheRoot),
            client = OkHttpClient(),
            contentResolver = { _, target ->
                when (calls) {
                    // 第一次：写 3 字节后返回 false（模拟 SAF 流中途失败，同 B 复现场景）
                    0 -> { target.outputStream().use { it.write(ByteArray(3) { 0x41 }) }; false }
                    // 第二次：真实拷贝 99 字节成功
                    else -> { target.outputStream().use { it.write(ByteArray(99) { 0x42 }) }; true }
                }.also { calls++ }
            },
            logger = StdoutLogger(),
        )
        val uri = "content://imported/sub.srt"
        val media = "https://media/stream.mkv"

        val first = subject.localPathFor(uri, media, "a4-atomic", emptyMap())

        assertNull("第一次拷贝失败必须返回 null", first)
        val residue = subtitlesDir().listFiles().orEmpty()
        assertTrue(
            "失败后 subtitles 目录不得残留任何文件（正式或 .part）：${residue.map { it.name }}",
            residue.isEmpty(),
        )

        val second = subject.localPathFor(uri, media, "a4-atomic", emptyMap())

        assertNotNull("第二次（拷贝可用）必须成功", second)
        assertEquals("第二次是真实重拷，内容是完整 99 字节而非半成品 3 字节", 99L, File(second!!).length())
        assertEquals("两次都执行了真实拷贝（无半成品复用）", 2, calls)
        assertTrue("落地后无 .part 残留", subtitlesDir().listFiles().orEmpty().none { it.name.endsWith(".part") })
    }
}
