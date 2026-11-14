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
}
