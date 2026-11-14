package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgePackTest {

    @Test
    fun `rename map round trips and is sorted`() {
        val renames = mapOf("local" to "我的本地", "emby" to "家里")
        assertEquals(renames, decodeBadgePackNames(encodeBadgePackNames(renames)))
    }

    @Test
    fun `blank names are dropped on encode and decode`() {
        assertTrue(decodeBadgePackNames(encodeBadgePackNames(mapOf("emby" to "   "))).isEmpty())
        assertTrue(decodeBadgePackNames("emby=|local=  ").isEmpty())
    }

    @Test
    fun `resolve uses rename then default`() {
        val pack = BuiltinBadgePacks.EMBY
        assertEquals("Emby 徽章", resolveBadgePackName(pack, emptyMap()))
        assertEquals("家里", resolveBadgePackName(pack, mapOf("emby" to "家里")))
        assertEquals("Emby 徽章", resolveBadgePackName(pack, mapOf("emby" to "  ")))
    }

    @Test
    fun `builtin packs have unique ids`() {
        val ids = BuiltinBadgePacks.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `custom packs round trip`() {
        val packs = listOf(
            BadgePack(id = "custom-1", defaultName = "家庭影院", custom = true),
            BadgePack(id = "custom-2", defaultName = "测试包", custom = true),
        )
        val decoded = decodeCustomBadgePacks(encodeCustomBadgePacks(packs))
        assertEquals(packs, decoded)
        assertTrue(decoded.all { it.custom })
    }

    @Test
    fun `custom pack encode skips malformed and blank names`() {
        assertTrue(decodeCustomBadgePacks("broken\n\t无名\ncustom-9\t").isEmpty())
        assertTrue(decodeCustomBadgePacks(null).isEmpty())
        assertEquals("", encodeCustomBadgePacks(emptyList()))
    }

    @Test
    fun `next custom id avoids collisions`() {
        assertEquals("custom-1", nextCustomBadgePackId(emptyList()))
        assertEquals("custom-2", nextCustomBadgePackId(listOf("emby", "custom-1")))
        assertEquals("custom-4", nextCustomBadgePackId(listOf("custom-1", "custom-3")))
    }

    @Test
    fun `delete removes custom packs but protects builtins`() {
        val all = allBadgePacks(listOf(BadgePack(id = "custom-1", defaultName = "我的", custom = true)))
        assertEquals(4, all.size)
        assertEquals(3, deleteBadgePack(all, "custom-1").size)
        assertEquals(4, deleteBadgePack(all, "emby").size)
    }

    @Test
    fun `builtin packs are not custom`() {
        assertTrue(BuiltinBadgePacks.all.none { it.custom })
    }
}
