package com.mediahub.core.common.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSourceTypesTest {

    @Test
    fun contract_vocabulary_has_exactly_the_twelve_wire_values() {
        assertEquals(
            listOf(
                "emby", "jellyfin", "plex", "webdav", "smb", "local",
                "fn-nas", "aliyun-drive", "baidu-drive", "quark-drive",
                "china-mobile-cloud", "tianyi-cloud",
            ),
            SyncSourceTypes.ALL,
        )
    }

    @Test
    fun contract_vocabulary_matches_the_documented_constants() {
        assertEquals("emby", SyncSourceTypes.EMBY)
        assertEquals("jellyfin", SyncSourceTypes.JELLYFIN)
        assertEquals("plex", SyncSourceTypes.PLEX)
        assertEquals("webdav", SyncSourceTypes.WEBDAV)
        assertEquals("smb", SyncSourceTypes.SMB)
        assertEquals("local", SyncSourceTypes.LOCAL)
        assertEquals("fn-nas", SyncSourceTypes.FN_NAS)
        assertEquals("aliyun-drive", SyncSourceTypes.ALIYUN_DRIVE)
        assertEquals("baidu-drive", SyncSourceTypes.BAIDU_DRIVE)
        assertEquals("quark-drive", SyncSourceTypes.QUARK_DRIVE)
        assertEquals("china-mobile-cloud", SyncSourceTypes.CHINA_MOBILE_CLOUD)
        assertEquals("tianyi-cloud", SyncSourceTypes.TIANYI_CLOUD)
    }

    @Test
    fun is_known_ignores_case_and_surrounding_whitespace() {
        assertTrue(SyncSourceTypes.isKnown("  Emby "))
        assertTrue(SyncSourceTypes.isKnown("WEBDAV"))
        assertTrue(SyncSourceTypes.isKnown("fn-nas"))
    }

    @Test
    fun is_known_rejects_unknown_and_blank_values() {
        assertFalse(SyncSourceTypes.isKnown("quantum-nas"))
        assertFalse(SyncSourceTypes.isKnown(""))
        assertFalse(SyncSourceTypes.isKnown("   "))
        assertFalse(SyncSourceTypes.isKnown(null))
    }
}
