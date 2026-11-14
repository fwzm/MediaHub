package com.mediahub.provider.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses attributes groups and urls`() {
        val playlist = M3uParser.parse(FIXTURE)

        assertEquals("http://127.0.0.1:1/epg.xml", playlist.epgUrl)
        assertEquals(4, playlist.channels.size)

        val cctv1 = playlist.channels[0]
        assertEquals("cctv1", cctv1.id)
        assertEquals("CCTV-1 综合", cctv1.name)
        assertEquals("央视", cctv1.group)
        assertEquals("http://logo/1.png", cctv1.logoUrl)
        assertEquals("http://stream/1.m3u8", cctv1.streamUrl)
        assertEquals("cctv1", cctv1.epgChannelId)

        assertEquals("央视", playlist.channels[1].group)
        assertEquals("http://stream/2.ts", playlist.channels[1].streamUrl)

        // #EXTGRP 覆盖 group-title
        assertEquals("卫视", playlist.channels[2].group)
        assertEquals("cctv1", playlist.channels[0].id)
        assertEquals("hunan", playlist.channels[2].id)

        assertEquals("未分组", playlist.channels[3].group)
        assertNull(playlist.channels[3].tvgId)
    }

    @Test
    fun `duplicate tvg ids stay unique and stable`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="same",First
            http://a/1.m3u8
            #EXTINF:-1 tvg-id="same",Second
            http://a/2.m3u8
            #EXTINF:-1 tvg-id="same",Third
            http://a/3.m3u8
        """.trimIndent()

        val first = M3uParser.parse(text)
        val second = M3uParser.parse(text)

        assertEquals(listOf("same", "same#2", "same#3"), first.channels.map { it.id })
        assertEquals(first.channels.map { it.id }, second.channels.map { it.id })
    }

    @Test
    fun `generated ids are stable for channels without tvg id`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Alpha
            http://a/live/alpha.m3u8
        """.trimIndent()

        val a = M3uParser.parse(text).channels.single()
        val b = M3uParser.parse(text).channels.single()

        assertTrue(a.id.startsWith("iptv-"))
        assertEquals(a.id, b.id)
        assertEquals("Alpha", a.name)
    }

    @Test
    fun `handles bom crlf and ignores unsupported directives and malformed urls`() {
        val text = "\uFEFF#EXTM3U\r\n" +
            "#EXTINF:-1 group-title=\"新闻\",Bad\r\n" +
            "not-a-url\r\n" +
            "#EXTINF:-1 group-title=\"新闻\",Good\r\n" +
            "#EXTVLCOPT:http-user-agent=test\r\n" +
            "#KODIPROP:inputstream=adaptive\r\n" +
            "https://example.test/live/good.m3u8\r\n"

        val playlist = M3uParser.parse(text)

        assertEquals(1, playlist.channels.size)
        assertEquals("Good", playlist.channels.single().name)
        assertEquals("新闻", playlist.channels.single().group)
    }

    @Test
    fun `extinf name may contain commas`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="G",News, Weather and Sports
            http://a/n.m3u8
        """.trimIndent()

        assertEquals("News, Weather and Sports", M3uParser.parse(text).channels.single().name)
    }

    @Test
    fun `missing header is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            M3uParser.parse("#EXTINF:-1,No header\nhttp://a/1.m3u8")
        }
        assertNotNull(error.message)
    }

    @Test
    fun `x-tvg-url alias is recognized`() {
        val text = """
            #EXTM3U x-tvg-url="https://epg.example/guide.xml"
            #EXTINF:-1,A
            http://a/1.m3u8
        """.trimIndent()

        assertEquals("https://epg.example/guide.xml", M3uParser.parse(text).epgUrl)
    }

    private companion object {
        const val FIXTURE = """#EXTM3U url-tvg="http://127.0.0.1:1/epg.xml"
#EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV-1" tvg-logo="http://logo/1.png" group-title="央视",CCTV-1 综合
http://stream/1.m3u8
#EXTINF:-1 group-title="央视",CCTV-2
#EXTVLCOPT:http-user-agent=test
http://stream/2.ts
#EXTGRP:卫视
#EXTINF:-1 tvg-id="hunan",湖南卫视
http://stream/3.m3u8?token=abc
#EXTINF:-1,无分组频道
http://stream/4.m3u8
"""
    }
}
