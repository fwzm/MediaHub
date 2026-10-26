package com.mediahub.core.common.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配对帧：4 字节大端长度 + 载荷；无 magic、无帧内版本（与 Windows 一致）。 */
class PairingFrameTest {

    @Test
    fun encode_writes_a_big_endian_length_header() {
        val framed = PairingFrame.encode(byteArrayOf(1, 2, 3))
        assertEquals(7, framed.size)
        assertEquals(0, framed[0].toInt())
        assertEquals(0, framed[1].toInt())
        assertEquals(0, framed[2].toInt())
        assertEquals(3, framed[3].toInt())
        assertArrayEquals(byteArrayOf(1, 2, 3), PairingFrame.decodeOrNull(framed))
        assertEquals(4, PairingFrame.HEADER_BYTES)
    }

    @Test
    fun encode_accepts_up_to_the_maximum_and_rejects_the_rest() {
        val maximum = ByteArray(PairingFrame.MAXIMUM_PAYLOAD_BYTES) { 0x5A }
        val framed = PairingFrame.encode(maximum)
        assertEquals(PairingFrame.MAXIMUM_PAYLOAD_BYTES + PairingFrame.HEADER_BYTES, framed.size)
        assertArrayEquals(maximum, PairingFrame.decodeOrNull(framed))
        assertEquals(8192, PairingFrame.MAXIMUM_PAYLOAD_BYTES)
        assertTrue(
            runCatching { PairingFrame.encode(ByteArray(PairingFrame.MAXIMUM_PAYLOAD_BYTES + 1)) }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(runCatching { PairingFrame.encode(ByteArray(0)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun decode_ignores_trailing_bytes_after_the_declared_length() {
        val framed = PairingFrame.encode(byteArrayOf(9, 8, 7))
        val padded = framed + byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertArrayEquals(byteArrayOf(9, 8, 7), PairingFrame.decodeOrNull(padded))
    }

    @Test
    fun decode_rejects_every_structural_problem() {
        assertNull(PairingFrame.decodeOrNull(null))
        assertNull(PairingFrame.decodeOrNull(ByteArray(0)))
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0, 0, 1)))
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0, 0, 0, 0)))
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0, 0, 0x20, 0x01)))
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0, 0, 0, 16)))
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0, 0, 0, 16, 0x6F, 0x6E, 0x6C, 0x79)))
    }

    @Test
    fun decode_rejects_a_huge_declared_length_without_allocating_it() {
        val header = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertNull(PairingFrame.decodeOrNull(header))
    }

    @Test
    fun text_frames_round_trip_utf8_content() {
        val json = "{\"deviceId\":\"设备-1\",\"note\":\"配对\"}"
        val framed = PairingFrame.encodeText(json)
        assertEquals(json, PairingFrame.decodeTextOrNull(framed))
        assertNull(PairingFrame.decodeTextOrNull(null))
        assertNull(PairingFrame.decodeTextOrNull(byteArrayOf(0, 0, 0, 0)))
    }
}
