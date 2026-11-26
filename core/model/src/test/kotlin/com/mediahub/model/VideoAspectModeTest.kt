package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 条目 9：画面比例模式与按内容记忆。 */
class VideoAspectModeTest {
    @Test
    fun `cycle order covers every mode and wraps`() {
        var mode = VideoAspectMode.FIT
        val seen = buildList {
            repeat(VideoAspectMode.entries.size) {
                add(mode)
                mode = mode.next()
            }
        }
        assertEquals(VideoAspectMode.entries.toList(), seen)
        assertEquals(VideoAspectMode.FIT, mode)
    }

    @Test
    fun `only explicit ratio modes pin a fixed ratio`() {
        assertEquals(16f / 9f, VideoAspectMode.RATIO_16_9.fixedAspectRatio)
        assertEquals(4f / 3f, VideoAspectMode.RATIO_4_3.fixedAspectRatio)
        assertNull(VideoAspectMode.FIT.fixedAspectRatio)
        assertNull(VideoAspectMode.CROP.fixedAspectRatio)
    }

    @Test
    fun `memory round trips server and item ids containing separators`() {
        val overrides = mapOf(
            VideoAspectMemory.key("server/a", "item\u001F=1") to VideoAspectMode.CROP,
            VideoAspectMemory.key("server-b", "带中文/路径.mp4") to VideoAspectMode.RATIO_4_3,
        )
        val encoded = VideoAspectMemory.encode(overrides)
        assertEquals(overrides, VideoAspectMemory.decode(encoded))
    }

    @Test
    fun `decode ignores malformed records instead of throwing`() {
        val stored = listOf(
            "server/item\u001FNOT_A_MODE",
            "missing-field",
            "\u001F",
            "ok/item\u001FFIT",
        ).joinToString("\u001E")
        assertEquals(mapOf("ok/item" to VideoAspectMode.FIT), VideoAspectMemory.decode(stored))
        assertTrue(VideoAspectMemory.decode(null).isEmpty())
    }

    @Test
    fun `remember keeps newest entries and evicts oldest beyond the cap`() {
        var current = emptyMap<String, VideoAspectMode>()
        repeat(VideoAspectMemory.MAX_ENTRIES) { index ->
            current = VideoAspectMemory.remember(
                current,
                "server/item-$index",
                if (index % 2 == 0) VideoAspectMode.FIT else VideoAspectMode.CROP,
            )
        }
        assertEquals(VideoAspectMemory.MAX_ENTRIES, current.size)
        current = VideoAspectMemory.remember(current, "server/new", VideoAspectMode.RATIO_16_9)
        assertEquals(VideoAspectMemory.MAX_ENTRIES, current.size)
        assertEquals(VideoAspectMode.RATIO_16_9, current["server/new"])
        assertEquals(null, current["server/item-0"])
        assertEquals(VideoAspectMode.CROP, current["server/item-1"])
    }

    @Test
    fun `remember updates existing key in place without growing`() {
        val current = mapOf(
            "server/a" to VideoAspectMode.FIT,
            "server/b" to VideoAspectMode.FIT,
        )
        val updated = VideoAspectMemory.remember(current, "server/a", VideoAspectMode.CROP)
        assertEquals(2, updated.size)
        assertEquals(VideoAspectMode.CROP, updated["server/a"])
        assertEquals(listOf("server/b", "server/a"), updated.keys.toList())
    }

    @Test
    fun `shared subtitle baseline matches mpv 720p canvas`() {
        assertEquals(38f, SubtitleStyle.BASE_FONT_SIZE_AT_720P)
        assertEquals(38f / 720f, SubtitleStyle.BASE_FONT_FRACTION_OF_VIDEO_HEIGHT, 0.0000001f)
    }
}
