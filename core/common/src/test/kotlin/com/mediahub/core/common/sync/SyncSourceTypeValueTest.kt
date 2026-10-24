package com.mediahub.core.common.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSourceTypeValueTest {

    @Test
    fun raw_is_preserved_verbatim_while_wire_is_normalised() {
        val value = SyncSourceTypeValue.parse("  Emby ")
        assertEquals("  Emby ", value.raw)
        assertEquals("emby", value.wire)
        assertTrue(value.isKnown)
    }

    @Test
    fun null_parse_is_legal_and_means_no_value() {
        val value = SyncSourceTypeValue.parse(null)
        assertEquals("", value.raw)
        assertEquals("", value.wire)
        assertFalse(value.isPresent)
        assertFalse(value.isKnown)
    }

    @Test
    fun two_spellings_are_the_same_kind_but_not_the_same_value() {
        val lower = SyncSourceTypeValue.parse("emby")
        val upper = SyncSourceTypeValue.parse("Emby")
        assertEquals(lower.wire, upper.wire)
        assertNotEquals(lower, upper)
    }

    @Test
    fun equality_and_hashcode_follow_the_raw_string() {
        assertEquals(SyncSourceTypeValue.parse("plex"), SyncSourceTypeValue.parse("plex"))
        assertEquals(
            SyncSourceTypeValue.parse("plex").hashCode(),
            SyncSourceTypeValue.parse("plex").hashCode(),
        )
    }

    @Test
    fun unknown_value_still_reports_wire_so_the_sender_spelling_survives() {
        val value = SyncSourceTypeValue.parse("Quantum-NAS")
        assertEquals("quantum-nas", value.wire)
        assertFalse(value.isKnown)
        assertTrue(value.isPresent)
    }

    @Test
    fun whitespace_only_value_is_not_present() {
        val value = SyncSourceTypeValue.parse("   ")
        assertFalse(value.isPresent)
        assertEquals("", value.wire)
    }
}
