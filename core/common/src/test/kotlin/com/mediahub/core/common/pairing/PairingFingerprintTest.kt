package com.mediahub.core.common.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 公钥指纹的规范化（小写 hex）、展示形式与常量时间比较。 */
class PairingFingerprintTest {

    @Test
    fun from_bytes_emits_lowercase_hex() {
        val bytes = ByteArray(32) { index -> (0xA0 + index).toByte() }
        val fingerprint = PairingFingerprint.fromBytes(bytes)
        assertEquals(64, fingerprint.hex.length)
        assertEquals("a0a1a2a3", fingerprint.hex.substring(0, 8))
        assertEquals(fingerprint.hex, fingerprint.hex.lowercase())
        assertEquals(32, fingerprint.bytes.size)
        assertEquals(64, PairingFingerprint.HEX_LENGTH)
    }

    @Test
    fun from_bytes_requires_exactly_32_bytes() {
        for (size in listOf(0, 31, 33)) {
            val error = runCatching { PairingFingerprint.fromBytes(ByteArray(size)) }.exceptionOrNull()
            assertTrue("size=" + size, error is IllegalArgumentException)
        }
    }

    @Test
    fun try_parse_accepts_uppercase_but_canonicalizes_to_lowercase() {
        val uppercase = "A0A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBF"
        val parsed = PairingFingerprint.tryParse(uppercase)
        assertNotNull(parsed)
        assertEquals(uppercase.lowercase(), parsed?.hex)
        assertNotNull(PairingFingerprint.tryParse(uppercase.lowercase()))
    }

    @Test
    fun try_parse_rejects_wrong_length_and_non_hex() {
        assertNull(PairingFingerprint.tryParse(null))
        assertNull(PairingFingerprint.tryParse(""))
        assertNull(PairingFingerprint.tryParse("a0a1"))
        assertNull(PairingFingerprint.tryParse("z0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"))
        assertNull(PairingFingerprint.tryParse("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbe"))
    }

    @Test
    fun parse_raises_the_bad_fingerprint_reason() {
        val error = runCatching { PairingFingerprint.parse("abc") }.exceptionOrNull()
        assertTrue(error is PairingContractException)
        assertEquals(PairingReasons.BAD_FINGERPRINT, (error as PairingContractException).reason)
        assertEquals(
            "bad-fingerprint",
            runCatching { PairingFingerprint.parse("z" + "0".repeat(63)) }
                .exceptionOrNull()
                .let { (it as PairingContractException).reason },
        )
    }

    @Test
    fun display_string_groups_four_characters_uppercase() {
        val fingerprint = PairingFingerprint.fromBytes(ByteArray(32) { index -> (0xA0 + index).toByte() })
        val display = fingerprint.displayString
        assertEquals(
            "A0A1 A2A3 A4A5 A6A7 A8A9 AAAB ACAD AEAF B0B1 B2B3 B4B5 B6B7 B8B9 BABB BCBD BEBF",
            display,
        )
        assertEquals(15, display.count { it == ' ' })
        assertEquals("", PairingFingerprint.EMPTY.displayString)
    }

    @Test
    fun matches_is_constant_time_and_rejects_empty_sides() {
        val left = PairingFingerprint.fromBytes(ByteArray(32) { 1 })
        val same = PairingFingerprint.fromBytes(ByteArray(32) { 1 })
        val other = PairingFingerprint.fromBytes(ByteArray(32) { 2 })
        assertTrue(left.matches(same))
        assertFalse(left.matches(other))
        assertFalse(left.matches(null))
        assertFalse(left.matches(PairingFingerprint.EMPTY))
        assertFalse(PairingFingerprint.EMPTY.matches(PairingFingerprint.EMPTY))
    }

    @Test
    fun equality_follows_the_canonical_hex_and_to_string_shows_it() {
        val first = PairingFingerprint.tryParse("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf")
        val second = PairingFingerprint.tryParse("A0A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBF")
        assertEquals(first, second)
        assertEquals(first?.hashCode(), second?.hashCode())
        assertEquals("PairingFingerprint(" + first?.hex + ")", first.toString())
        assertEquals(0, PairingFingerprint.EMPTY.hex.length)
        assertTrue(PairingFingerprint.EMPTY.isEmpty)
    }

    @Test
    fun ascii_hex_predicate_matches_the_windows_whitelist() {
        for (character in "0123456789abcdefABCDEF") {
            assertTrue(character.toString(), PairingFingerprint.isAsciiHexDigit(character))
        }
        for (character in "ghzG !@") {
            assertFalse(character.toString(), PairingFingerprint.isAsciiHexDigit(character))
        }
    }
}
