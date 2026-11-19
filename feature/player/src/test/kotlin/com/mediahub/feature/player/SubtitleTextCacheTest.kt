package com.mediahub.feature.player

import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTextCacheTest {

    @Test
    fun `gbk subtitle is rewritten as utf8`() {
        val dir = Files.createTempDirectory("subtitle-cache").toFile()
        val cache = SubtitleTextCache(dir)
        val text = "中文字幕\n第二行"
        val file = cache.prepare(text.toByteArray(Charset.forName("GBK")), "srt")
        assertTrue(file != null)
        assertEquals(text, file!!.readText(Charsets.UTF_8))
        assertTrue(file.name.endsWith(".srt"))
    }

    @Test
    fun `utf8 subtitle does not create a copy`() {
        val dir = Files.createTempDirectory("subtitle-cache").toFile()
        val cache = SubtitleTextCache(dir)
        assertNull(cache.prepare("字幕".toByteArray(Charsets.UTF_8), "srt"))
    }

    @Test
    fun `same bytes reuse the same cache file`() {
        val dir = Files.createTempDirectory("subtitle-cache").toFile()
        val cache = SubtitleTextCache(dir)
        val bytes = "字幕".toByteArray(Charset.forName("GBK"))
        assertEquals(cache.prepare(bytes, "srt"), cache.prepare(bytes, "srt"))
    }
}
