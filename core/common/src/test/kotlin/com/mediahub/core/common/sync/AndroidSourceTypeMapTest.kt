package com.mediahub.core.common.sync

import com.mediahub.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSourceTypeMapTest {

    @Test
    fun every_contract_wire_value_has_an_android_server_type() {
        for (wire in SyncSourceTypes.ALL) {
            assertTrue("缺少映射: " + wire, AndroidSourceTypeMap.toServerType(wire) != null)
        }
        assertEquals(SyncSourceTypes.ALL, AndroidSourceTypeMap.supportedWireValues)
    }

    @Test
    fun mapping_round_trips_through_the_enum() {
        for (wire in SyncSourceTypes.ALL) {
            val type = AndroidSourceTypeMap.toServerType(wire)
            assertEquals(wire, AndroidSourceTypeMap.toWire(type))
        }
    }

    @Test
    fun unknown_wire_value_keeps_the_raw_string_and_has_no_type() {
        val resolution = AndroidSourceTypeMap.resolve("Quantum-NAS")
        assertFalse(resolution.isKnown)
        assertFalse(resolution.isSupported)
        assertNull(resolution.serverType)
        assertEquals("Quantum-NAS", resolution.sourceType.raw)
        assertEquals("quantum-nas", resolution.sourceType.wire)
    }

    @Test
    fun known_value_reports_both_known_and_supported() {
        val resolution = AndroidSourceTypeMap.resolve("weBdAv")
        assertTrue(resolution.isKnown)
        assertTrue(resolution.isSupported)
        assertEquals(ServerType.WEBDAV, resolution.serverType)
        assertEquals("weBdAv", resolution.sourceType.raw)
    }

    @Test
    fun null_wire_value_yields_no_type() {
        assertNull(AndroidSourceTypeMap.toServerType(null))
        assertNull(AndroidSourceTypeMap.toWire(null))
    }

    @Test
    fun the_table_has_one_row_per_wire_value_and_no_duplicates() {
        assertEquals(12, AndroidSourceTypeMap.mappings.size)
        assertEquals(12, AndroidSourceTypeMap.mappings.map { it.wire }.toSet().size)
        assertEquals(12, AndroidSourceTypeMap.mappings.map { it.serverType }.toSet().size)
    }
}
