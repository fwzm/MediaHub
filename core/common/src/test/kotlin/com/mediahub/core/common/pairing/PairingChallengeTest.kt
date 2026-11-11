package com.mediahub.core.common.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** challenge 的规范化、比较与脱敏行为。 */
class PairingChallengeTest {

    @Test
    fun from_bytes_uses_canonical_padded_base64() {
        val bytes = ByteArray(32) { index -> index.toByte() }
        val challenge = PairingChallenge.fromBytes(bytes)
        assertEquals(32, challenge.bytes.size)
        assertEquals(44, challenge.base64.length)
        assertTrue(challenge.base64.endsWith("="))
        assertEquals(Base64.getEncoder().encodeToString(bytes), challenge.base64)
        assertFalse(challenge.isEmpty)
    }

    @Test
    fun from_bytes_requires_exactly_32_bytes() {
        assertEquals(32, PairingChallenge.BYTE_LENGTH)
        for (size in listOf(0, 16, 31, 33, 64)) {
            val error = runCatching { PairingChallenge.fromBytes(ByteArray(size)) }.exceptionOrNull()
            assertTrue("size=" + size, error is IllegalArgumentException)
        }
    }

    @Test
    fun try_parse_accepts_only_the_canonical_spelling() {
        val canonical = PairingChallenge.fromBytes(ByteArray(32) { index -> (index * 2).toByte() })
        assertNotNull(PairingChallenge.tryParse(canonical.base64))
        assertNull(PairingChallenge.tryParse(null))
        assertNull(PairingChallenge.tryParse(""))
        assertNull(PairingChallenge.tryParse("   "))
        assertNull(PairingChallenge.tryParse("not base64!!"))
        // 插入一个空格：能解码但拼写不规范，必须拒绝（与 Windows 一致）。
        val spaced = canonical.base64.substring(0, 10) + " " + canonical.base64.substring(10)
        assertNull(PairingChallenge.tryParse(spaced))
        // 长度不是 32 字节也必须拒绝。
        assertNull(PairingChallenge.tryParse(Base64.getEncoder().encodeToString(ByteArray(16))))
        assertNull(PairingChallenge.tryParse(Base64.getEncoder().encodeToString(ByteArray(33))))
    }

    @Test
    fun parse_raises_the_bad_challenge_reason() {
        val error = runCatching { PairingChallenge.parse("zzz") }.exceptionOrNull()
        assertTrue(error is PairingContractException)
        assertEquals(PairingReasons.BAD_CHALLENGE, (error as PairingContractException).reason)
    }

    @Test
    fun matches_compares_content_and_rejects_empty_sides() {
        val left = PairingChallenge.fromBytes(ByteArray(32) { 7 })
        val same = PairingChallenge.fromBytes(ByteArray(32) { 7 })
        val other = PairingChallenge.fromBytes(ByteArray(32) { 8 })
        assertTrue(left.matches(same))
        assertFalse(left.matches(other))
        assertFalse(left.matches(null))
        assertFalse(left.matches(PairingChallenge.EMPTY))
        assertFalse(PairingChallenge.EMPTY.matches(left))
        assertFalse(PairingChallenge.EMPTY.matches(PairingChallenge.EMPTY))
    }

    @Test
    fun create_random_produces_distinct_32_byte_values() {
        val first = PairingChallenge.createRandom()
        val second = PairingChallenge.createRandom()
        assertEquals(32, first.bytes.size)
        assertFalse(first.matches(second))
        assertNotEquals(first.base64, second.base64)
    }

    @Test
    fun equality_is_by_canonical_text_and_to_string_redacts() {
        val first = PairingChallenge.fromBytes(ByteArray(32) { 3 })
        val second = PairingChallenge.fromBytes(ByteArray(32) { 3 })
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals("PairingChallenge(<redacted>)", first.toString())
        assertFalse(first.toString().contains(first.base64))
    }
}
