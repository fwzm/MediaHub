package com.mediahub.player.mpv

import com.mediahub.core.logging.StdoutLogger
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A4-C6 失效策略回归：LRU 上限淘汰 + 孤儿 .part 清理 + clearSession 会话清空。
 * （content:// 路径依赖 android.net.Uri，需 Robolectric。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class A4SubtitleCacheEvictionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun subject() = SubtitleCache(
        cacheDir = tmp.newFolder(),
        client = okhttp3.OkHttpClient(),
        contentResolver = { _, _ -> true },
        logger = StdoutLogger(),
    )

    private fun writeDirect(cacheDir: File, name: String, bytes: Int, ageMs: Long) {
        val dir = File(cacheDir, "subtitles").apply { mkdirs() }
        File(dir, name).writeBytes(ByteArray(bytes))
        File(dir, name).setLastModified(System.currentTimeMillis() - ageMs)
    }

    /** content:// 拷贝 fake：真实写入字节（空文件会被 fail-closed 拒绝）。 */
    private fun writingCopy() = SubtitleCache.ContentCopy { _, target ->
        target.outputStream().use { it.write(ByteArray(64) { 0x41 }) }
        true
    }

    @Test
    fun `eviction trims to file count bound and clears orphan parts`() = runBlocking {
        val dir = File(tmp.newFolder(), "subtitles").apply { mkdirs() }
        // 预置 70 个正式文件（超过 64 上限），越旧 lastModified 越早
        for (i in 0 until 70) {
            val f = File(dir, "f$i.srt")
            f.writeBytes(ByteArray(16))
            f.setLastModified(System.currentTimeMillis() - (1000L - i) * 1000L)
        }
        File(dir, "orphan1.srt.part").writeBytes(ByteArray(8))
        File(dir, "orphan2.srt.part").writeBytes(ByteArray(8))
        val shared = SubtitleCache(
            cacheDir = dir.parentFile,
            client = okhttp3.OkHttpClient(),
            contentResolver = writingCopy(),
            logger = StdoutLogger(),
        )

        val path = shared.localPathFor(
            uri = "content://import/evict.srt",
            mediaUrl = "https://media/stream.mkv",
            scopeKey = "scope-1",
            sessionHeaders = emptyMap(),
        )
        assertTrue(path != null)
        val formal = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }!!
        val parts = dir.listFiles { f -> f.name.endsWith(".part") }!!
        assertTrue("文件数必须回到上限内（实际 ${formal.size}）", formal.size <= 64)
        assertEquals("孤儿 .part 必须被清理", 0, parts.size)
    }

    @Test
    fun `clear session empties the subtitle cache directory`() = runBlocking {
        val s = SubtitleCache(
            cacheDir = tmp.newFolder(),
            client = okhttp3.OkHttpClient(),
            contentResolver = writingCopy(),
            logger = StdoutLogger(),
        )
        val path = s.localPathFor(
            uri = "content://import/x.srt",
            mediaUrl = "https://media/stream.mkv",
            scopeKey = "scope-2",
            sessionHeaders = emptyMap(),
        )
        assertTrue(path != null)
        val dir = File(path!!).parentFile!!
        assertTrue(dir.listFiles()!!.isNotEmpty())

        s.clearSession()

        assertEquals("会话清理后目录必须为空", 0, dir.listFiles()!!.size)
    }
}
