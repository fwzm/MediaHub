package com.mediahub.core.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuParserTest {

    @Test
    fun `parses bilibili xml with modes colors and entities`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <i>
              <d p="1.50,1,25,16777215,0,0,0,0">hello</d>
              <d p="2.00,5,25,16711680,0,0,0,0">top &amp; center</d>
              <d p="3.00,4,25,255,0,0,0,0">bottom</d>
            </i>
        """.trimIndent()

        val comments = DanmakuParser.parseXml(xml)

        assertEquals(3, comments.size)
        assertEquals(1500L, comments[0].timeMs)
        assertEquals(DanmakuMode.SCROLL, comments[0].mode)
        assertEquals(0xFFFFFFFF.toInt(), comments[0].colorArgb)
        assertEquals("top & center", comments[1].text)
        assertEquals(DanmakuMode.TOP, comments[1].mode)
        assertEquals(0xFFFF0000.toInt(), comments[1].colorArgb)
        assertEquals(DanmakuMode.BOTTOM, comments[2].mode)
    }

    @Test
    fun `xml skips malformed entries and caps oversized text`() {
        val longText = "x".repeat(DanmakuParser.MAX_TEXT_LENGTH + 50)
        val xml = """
            <i>
              <d p="bad">ignored</d>
              <d p="4.00,1,25,16777215,0,0,0,0">$longText</d>
              <d p="5.00,1,25,16777215,0,0,0,0">   </d>
              <d p="6.00,1,25,16777215,0,0,0,0">ok</d>
            </i>
        """.trimIndent()

        val comments = DanmakuParser.parseXml(xml)

        assertEquals(2, comments.size)
        assertEquals(DanmakuParser.MAX_TEXT_LENGTH, comments[0].text.length)
        assertEquals("ok", comments[1].text)
    }

    @Test
    fun `garbage payload never throws`() {
        assertTrue(DanmakuParser.parseXml("\u0000not xml at all").isEmpty())
        assertTrue(DanmakuParser.parseDanDanPlayJson("{broken").isEmpty())
        assertTrue(DanmakuParser.parseAuto("").isEmpty())
    }

    @Test
    fun `parses dandanplay json comments`() {
        val json = """
            {"count": 2, "comments": [
              {"cid": 1, "p": "1.25,1,16711680", "m": "hello"},
              {"cid": 2, "p": "2.50,5,16777215", "m": " top "}
            ]}
        """.trimIndent()

        val comments = DanmakuParser.parseDanDanPlayJson(json)

        assertEquals(2, comments.size)
        assertEquals(1250L, comments[0].timeMs)
        assertEquals(0xFFFF0000.toInt(), comments[0].colorArgb)
        assertEquals("top", comments[1].text)
        assertEquals(DanmakuMode.TOP, comments[1].mode)
    }

    @Test
    fun `json skips invalid entries`() {
        val json = """
            {"comments": [
              {"cid": 1, "p": "oops", "m": "bad time"},
              {"cid": 2, "p": "1.0", "m": ""},
              {"cid": 3, "p": "1.0", "m": "kept"}
            ]}
        """.trimIndent()

        val comments = DanmakuParser.parseDanDanPlayJson(json)

        assertEquals(1, comments.size)
        assertEquals("kept", comments[0].text)
    }

    @Test
    fun `parseAuto detects xml and json`() {
        val xml = "<i><d p=\"1,1,25,16777215\">x</d></i>"
        val json = """{"comments":[{"cid":9,"p":"2.0","m":"y"}]}"""

        assertEquals("x", DanmakuParser.parseAuto("  $xml").single().text)
        assertEquals("y", DanmakuParser.parseAuto(json).single().text)
    }
}
