package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动匹配持久化（收尾项 1）：选中结果要能跨重启生效，因此必须可序列化到 DataStore。
 */
class ManualMatchTest {

    @Test
    fun `records round trip with poster`() {
        val matches = mapOf(
            manualMatchKey("s1", "item-a") to ManualMatchRecord("t-a", "正确的电影", "https://x/p.jpg"),
            manualMatchKey("s2", "item-b") to ManualMatchRecord("t-b", "另一部", null),
        )
        val decoded = decodeManualMatches(encodeManualMatches(matches))
        assertEquals(matches, decoded)
        assertNull(decoded[manualMatchKey("s2", "item-b")]?.posterUrl)
    }

    @Test
    fun `keys separate the same item id across servers`() {
        assertTrue(manualMatchKey("s1", "x") != manualMatchKey("s2", "x"))
        val decoded = decodeManualMatches(
            encodeManualMatches(
                mapOf(
                    manualMatchKey("s1", "x") to ManualMatchRecord("t1", "一"),
                    manualMatchKey("s2", "x") to ManualMatchRecord("t2", "二"),
                ),
            ),
        )
        assertEquals("t1", decoded[manualMatchKey("s1", "x")]?.targetId)
        assertEquals("t2", decoded[manualMatchKey("s2", "x")]?.targetId)
    }

    @Test
    fun `separators inside names are sanitized`() {
        val encoded = encodeManualMatches(
            mapOf(manualMatchKey("s1", "i") to ManualMatchRecord("t", "第一行\n第二行\t结束")),
        )
        val decoded = decodeManualMatches(encoded)
        assertEquals("第一行 第二行 结束", decoded.values.first().title)
    }

    @Test
    fun `malformed lines are dropped`() {
        val decoded = decodeManualMatches("bad-line\n\nk\tonly-id\nk2\tt\t标题\t")
        assertEquals(1, decoded.size)
        assertEquals("标题", decoded["k2"]?.title)
        assertNull(decoded["k2"]?.posterUrl)
    }

    @Test
    fun `blank input decodes to empty map`() {
        assertTrue(decodeManualMatches(null).isEmpty())
        assertTrue(decodeManualMatches("").isEmpty())
        assertTrue(decodeManualMatches("   ").isEmpty())
        assertEquals("", encodeManualMatches(emptyMap()))
    }
}
