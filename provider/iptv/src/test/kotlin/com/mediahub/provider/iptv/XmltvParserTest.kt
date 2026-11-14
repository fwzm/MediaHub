package com.mediahub.provider.iptv

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class XmltvParserTest {

    @Test
    fun `parses programmes with offsets into epoch millis`() {
        val programs = XmltvParser.parse(FIXTURE)

        assertEquals(3, programs.size)
        val news = programs.first { it.title == "新闻联播" }
        assertEquals("cctv1", news.channelId)
        assertEquals(Instant.parse("2026-11-14T11:00:00Z").toEpochMilli(), news.startEpochMs)
        assertEquals(Instant.parse("2026-11-14T12:00:00Z").toEpochMilli(), news.endEpochMs)
        assertEquals("新闻", news.description)
        assertEquals("http://logo/news.png", news.iconUrl)

        val talk = programs.first { it.title == "焦点访谈" }
        assertEquals(Instant.parse("2026-11-14T12:00:00Z").toEpochMilli(), talk.startEpochMs)
        assertNull(talk.description)
        assertNull(talk.iconUrl)
    }

    @Test
    fun `programme without offset is treated as utc`() {
        val program = XmltvParser.parse(FIXTURE).first { it.title == "UTC 节目" }
        assertEquals(Instant.parse("2026-11-14T20:00:00Z").toEpochMilli(), program.startEpochMs)
        assertEquals(Instant.parse("2026-11-14T21:00:00Z").toEpochMilli(), program.endEpochMs)
    }

    @Test
    fun `invalid times and inverted ranges are skipped`() {
        val titles = XmltvParser.parse(FIXTURE).map { it.title }
        assertEquals(setOf("新闻联播", "焦点访谈", "UTC 节目"), titles.toSet())
    }

    @Test
    fun `results are sorted by start time`() {
        val starts = XmltvParser.parse(FIXTURE).map { it.startEpochMs }
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun `parses channel display names`() {
        val names = XmltvParser.parseChannelNames(FIXTURE)
        assertEquals("CCTV-1 综合", names["cctv1"])
        assertEquals("CCTV-2", names["cctv2"])
    }

    @Test
    fun `doctype is rejected`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE tv SYSTEM "http://evil.example/xxe.dtd">
            <tv><programme start="20261114190000 +0800" stop="20261114200000 +0800" channel="a"><title>x</title></programme></tv>
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) { XmltvParser.parse(xml) }
        assertNotNull(error.message)
    }

    private companion object {
        val FIXTURE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <channel id="cctv2"><display-name>CCTV-2</display-name></channel>
              <programme start="20261114190000 +0800" stop="20261114200000 +0800" channel="cctv1">
                <title lang="zh">新闻联播</title>
                <desc>新闻</desc>
                <icon src="http://logo/news.png"/>
              </programme>
              <programme start="20261114200000 +0800" stop="20261114210000 +0800" channel="cctv1">
                <title>焦点访谈</title>
              </programme>
              <programme start="20261114200000" stop="20261114210000" channel="cctv2">
                <title>UTC 节目</title>
              </programme>
              <programme start="bad" stop="20261114210000 +0800" channel="cctv1">
                <title>坏时间</title>
              </programme>
              <programme start="20261114210000 +0800" stop="20261114200000 +0800" channel="cctv1">
                <title>倒序</title>
              </programme>
            </tv>
        """.trimIndent()
    }
}
