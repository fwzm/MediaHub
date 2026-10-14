package com.mediahub.provider.local

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 本地存储同目录字幕发现（P2 字幕中心切片一）：
 * 扩展名过滤 / 语言尾缀 / file:// uri / 排序 / 无字幕与无效路径空列表。
 */
class LocalSubtitleDiscoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MediaServer(
        id = "srv-local", name = "本地", type = ServerType.LOCAL,
        baseUrl = "file:///local", createdAtEpochMs = 0,
    )

    private fun provider() = LocalProvider(server, object : LocalRootProvider {
        override fun rootDirectories(): List<File> = emptyList()
    })

    private fun videoItem(file: File): MediaItem = MediaItem(
        serverId = server.id,
        id = file.absolutePath,
        type = MediaType.VIDEO,
        title = file.nameWithoutExtension,
        path = file.absolutePath,
    )

    @Test
    fun `discovers subtitle siblings with language suffix and file uri`() = runBlocking {
        val dir = tmp.newFolder("movies")
        val video = File(dir, "film.mkv").apply { writeText("v") }
        val srt = File(dir, "film.zh.srt").apply { writeText("srt") }
        val ass = File(dir, "film.eng.ass").apply { writeText("ass") }
        val vtt = File(dir, "notes.vtt").apply { writeText("vtt") }
        File(dir, "poster.jpg").writeText("img")
        File(dir, "archive.zip").writeText("zip")

        val found = provider().discoverSubtitles(videoItem(video))

        assertEquals(3, found.size)
        val zh = found.first { it.fileName == "film.zh.srt" }
        assertEquals("zh", zh.language)
        assertEquals("film.zh", zh.name)
        assertEquals("srt", zh.extension)
        assertEquals("application/x-subrip", zh.mimeType)
        assertEquals(srt.toURI().toString(), zh.uri)
        assertEquals(srt.absolutePath, zh.id)
        assertEquals("en", found.first { it.fileName == "film.eng.ass" }.language)
        assertTrue(found.any { it.fileName == "notes.vtt" })
        // jpg/zip 被过滤
        assertTrue(found.none { it.extension == "jpg" || it.extension == "zip" })
        assertNull(found.first { it.fileName == "notes.vtt" }.language)
    }

    @Test
    fun `folder without subtitles returns empty list`() = runBlocking {
        val dir = tmp.newFolder("empty")
        val video = File(dir, "film.mkv").apply { writeText("v") }
        File(dir, "poster.jpg").writeText("img")

        val found = provider().discoverSubtitles(videoItem(video))

        assertTrue(found.isEmpty())
    }

    @Test
    fun `missing video or directory returns empty list instead of throwing`() = runBlocking {
        val missing = File(tmp.root, "nope/film.mkv")
        assertTrue(provider().discoverSubtitles(videoItem(missing)).isEmpty())
        // 视频路径本身是目录：无父级视频语义，返回空
        val dirVideo = tmp.newFolder("just-dir")
        assertTrue(provider().discoverSubtitles(videoItem(dirVideo)).isEmpty())
    }

    @Test
    fun `results sorted by name then extension`() = runBlocking {
        val dir = tmp.newFolder("sorted")
        val video = File(dir, "film.mkv").apply { writeText("v") }
        File(dir, "b.srt").writeText("x")
        File(dir, "a.ass").writeText("x")
        File(dir, "a.srt").writeText("x")

        val found = provider().discoverSubtitles(videoItem(video))

        assertEquals(listOf("a.ass", "a.srt", "b.srt"), found.map { it.fileName })
    }
}
