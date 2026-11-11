package com.mediahub.core.common.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配对码与配对链接的编码、规范化与错误映射。 */
class PairingLinkTest {

    private val now = 1_700_000_000_000L

    @Test
    fun create_and_extract_round_trip() {
        val payload = samplePayload(now)
        val link = PairingLink.create(payload)
        assertTrue(link.startsWith("mediahub://pair?"))
        assertFalse(link.contains("code="))
        assertEquals(PairingCode.encode(payload), PairingLink.extractCode(link))
        val parsed = PairingLink.parse(link, now)
        assertEquals("ok", parsed.reasonOrOk)
        assertEquals("phone-1", parsed.payload?.deviceId)
        assertEquals(payload.challenge.base64, parsed.payload?.challenge?.base64)
    }

    @Test
    fun extract_tolerates_code_parameter_uppercase_prefix_and_whitespace() {
        val code = PairingCode.encode(samplePayload(now))
        assertEquals(code, PairingLink.extractCode("mediahub://pair?code=" + code))
        assertEquals(code, PairingLink.extractCode("MEDIAHUB://PAIR?" + code))
        assertEquals(code, PairingLink.extractCode("  mediahub://pair?" + code + "\n"))
        assertEquals(code, PairingLink.extractCode("mediahub://pair?  " + code + "  "))
        assertEquals("", PairingLink.extractCode(null))
        assertEquals("", PairingLink.extractCode("https://example.com/pair?code=" + code))
        assertEquals(PairingLinkReasons.NOT_A_LINK, PairingLink.parse("mediahub://pair/abc", now).reasonOrOk)
    }

    @Test
    fun parse_reports_the_windows_reasons() {
        assertEquals(PairingLinkReasons.EMPTY, PairingLink.parse(null, now).reasonOrOk)
        assertEquals(PairingLinkReasons.EMPTY, PairingLink.parse("", now).reasonOrOk)
        assertEquals(PairingLinkReasons.EMPTY, PairingLink.parse("   ", now).reasonOrOk)
        assertEquals(PairingLinkReasons.NOT_A_LINK, PairingLink.parse("mediahub://pair/abc", now).reasonOrOk)
        assertEquals(PairingLinkReasons.NOT_A_LINK, PairingLink.parse("https://example.com", now).reasonOrOk)
        assertEquals(
            PairingLinkReasons.TOO_LONG,
            PairingLink.parse(PairingLink.PREFIX + "a".repeat(PairingLink.MAXIMUM_LENGTH), now).reasonOrOk,
        )
        assertEquals(8192, PairingLink.MAXIMUM_LENGTH)
    }

    @Test
    fun parse_expires_at_the_boundary() {
        val payload = samplePayload(now)
        val link = PairingLink.create(payload)
        assertEquals("ok", PairingLink.parse(link, payload.expiresAtEpochMs - 1L).reasonOrOk)
        assertEquals(PairingReasons.EXPIRED, PairingLink.parse(link, payload.expiresAtEpochMs).reasonOrOk)
        assertEquals(PairingReasons.EXPIRED, PairingLink.parse(link, payload.expiresAtEpochMs + 1L).reasonOrOk)
    }

    @Test
    fun normalize_strips_only_whitespace_characters() {
        val code = PairingCode.encode(samplePayload(now))
        val messy = " " + code.substring(0, 10) + "\n\r\t " + code.substring(10) + " "
        assertEquals(code, PairingCode.normalize(messy))
        assertEquals("", PairingCode.normalize(null))
        assertEquals("abcDEF_-019", PairingCode.normalize(" abc DEF_-019 "))
    }

    @Test
    fun code_rejects_padding_and_illegal_characters() {
        val code = PairingCode.encode(samplePayload(now))
        assertEquals(PairingCodeReasons.NOT_BASE64URL, PairingCode.decode(code + "==", now).reasonOrOk)
        assertEquals(PairingCodeReasons.NOT_BASE64URL, PairingCode.decode(code + "%", now).reasonOrOk)
        assertEquals(PairingCodeReasons.NOT_BASE64URL, PairingCode.decode("!!!!", now).reasonOrOk)
        assertEquals(PairingCodeReasons.NOT_BASE64URL, PairingCode.decode("a", now).reasonOrOk)
        assertEquals(PairingCodeReasons.EMPTY, PairingCode.decode("", now).reasonOrOk)
        assertEquals(
            PairingCodeReasons.TOO_LONG,
            PairingCode.decode("a".repeat(PairingCode.MAXIMUM_LENGTH + 1), now).reasonOrOk,
        )
        assertEquals(4096, PairingCode.MAXIMUM_LENGTH)
    }

    @Test
    fun code_rejects_invalid_utf8_and_maps_inner_payload_reasons() {
        val invalidUtf8 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(byteArrayOf(0x7B, 0x7D, 0xFF.toByte()))
        assertEquals(PairingCodeReasons.PAYLOAD_INVALID, PairingCode.decode(invalidUtf8, now).reasonOrOk)
        val sample = samplePayload(now)
        val missingDeviceId = "{\"schemaVersion\":1," +
            "\"ephemeralUrl\":\"https://10.0.0.5:8942/pairing\"," +
            "\"challenge\":\"" + sample.challenge.base64 + "\"," +
            "\"publicKeyFingerprint\":\"" + sample.publicKeyFingerprint.hex + "\"," +
            "\"expiresAtEpochMs\":1700000120000}"
        val code = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(missingDeviceId.toByteArray(Charsets.UTF_8))
        assertEquals(PairingCodeReasons.PAYLOAD_INVALID, PairingCode.decode(code, now).reasonOrOk)
    }

    @Test
    fun payload_reason_mapping_matches_windows() {
        assertEquals(PairingReasons.BAD_URL, PairingCode.mapPayloadReason(PairingReasons.BAD_URL))
        assertEquals(PairingReasons.BAD_CHALLENGE, PairingCode.mapPayloadReason(PairingReasons.BAD_CHALLENGE))
        assertEquals(PairingReasons.BAD_FINGERPRINT, PairingCode.mapPayloadReason(PairingReasons.BAD_FINGERPRINT))
        assertEquals(PairingReasons.UNSUPPORTED_VERSION, PairingCode.mapPayloadReason(PairingReasons.UNSUPPORTED_VERSION))
        assertEquals(PairingReasons.EXPIRED, PairingCode.mapPayloadReason(PairingReasons.EXPIRED))
        assertEquals(PairingCodeReasons.PAYLOAD_INVALID, PairingCode.mapPayloadReason(PairingReasons.MALFORMED_JSON))
        assertEquals(
            PairingCodeReasons.PAYLOAD_INVALID,
            PairingCode.mapPayloadReason(PairingReasons.missingField("deviceId")),
        )
        assertEquals(
            PairingCodeReasons.PAYLOAD_INVALID,
            PairingCode.mapPayloadReason(PairingReasons.invalidField("expiresAtEpochMs")),
        )
    }

    @Test
    fun base64url_character_whitelist_matches_windows() {
        for (character in "AZaz09-_") {
            assertTrue(character.toString(), PairingCode.isBase64UrlCharacter(character))
        }
        for (character in "=+/ .!") {
            assertFalse(character.toString(), PairingCode.isBase64UrlCharacter(character))
        }
    }

    private fun samplePayload(nowEpochMs: Long): PairingPayload = PairingPayload.create(
        deviceId = "phone-1",
        ephemeralUrl = "https://10.0.0.5:8942/pairing",
        challenge = PairingChallenge.fromBytes(ByteArray(32) { index -> index.toByte() }),
        publicKeyFingerprint = PairingFingerprint.fromBytes(ByteArray(32) { index -> (0xA0 + index).toByte() }),
        nowEpochMs = nowEpochMs,
    )
}
