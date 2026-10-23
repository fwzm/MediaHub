package com.mediahub.core.common.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对载荷的结构/时效校验与编解码行为（不经向量文件，直接用构造出来的值）。
 */
class PairingPayloadTest {

    @Test
    fun create_expires_after_the_contract_lifetime() {
        val now = 1_700_000_000_000L
        val payload = PairingPayload.create(
            deviceId = "phone-1",
            ephemeralUrl = "https://10.0.0.5:8942/pairing",
            challenge = challenge(0x11),
            publicKeyFingerprint = fingerprint(0x22),
            nowEpochMs = now,
        )
        assertEquals(PairingPayload.CURRENT_SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(now + 120_000L, payload.expiresAtEpochMs)
        assertEquals(PairingPayload.LIFETIME_SECONDS, 120)
        assertEquals(PairingPayload.LIFETIME_MILLISECONDS, 120_000L)
    }

    @Test
    fun create_rejects_a_blank_device_id() {
        val error = assertFailsWithType<PairingContractException> {
            PairingPayload.create(" ", "https://10.0.0.5:8942/pairing", challenge(1), fingerprint(1), 0L)
        }
        assertEquals(PairingReasons.missingField("deviceId"), error.reason)
    }

    @Test
    fun create_rejects_a_url_with_a_query() {
        val error = assertFailsWithType<PairingContractException> {
            PairingPayload.create("phone-1", "https://10.0.0.5:8942/pairing?x=1", challenge(1), fingerprint(1), 0L)
        }
        assertEquals(PairingReasons.BAD_URL, error.reason)
    }

    @Test
    fun encode_uses_the_windows_field_order_and_no_whitespace() {
        val payload = PairingPayload(
            deviceId = "phone-1",
            ephemeralUrl = "https://10.0.0.5:8942/pairing",
            challenge = challenge(0x11),
            publicKeyFingerprint = fingerprint(0x22),
            expiresAtEpochMs = 1_700_000_120_000L,
        )
        val encoded = PairingPayloadCodec.encode(payload)
        assertFalse(encoded.contains(" "))
        assertTrue(encoded.startsWith("{\"schemaVersion\":1,\"deviceId\":\"phone-1\",\"ephemeralUrl\":\"https://10.0.0.5:8942/pairing\",\"challenge\":\""))
        assertTrue(encoded.endsWith("\"expiresAtEpochMs\":1700000120000}"))
        assertTrue(encoded.indexOf("\"challenge\"") < encoded.indexOf("\"publicKeyFingerprint\""))
    }

    @Test
    fun encode_refuses_an_invalid_payload() {
        val broken = PairingPayload(deviceId = "", expiresAtEpochMs = 1L)
        assertFailsWithType<PairingContractException> { PairingPayloadCodec.encode(broken) }
    }

    @Test
    fun decode_round_trips_a_wire_payload() {
        val original = PairingPayload(
            deviceId = "phone-1",
            ephemeralUrl = "https://10.0.0.5:8942/pairing",
            challenge = challenge(0x33),
            publicKeyFingerprint = fingerprint(0x44),
            expiresAtEpochMs = 42L,
        )
        val restored = PairingPayloadCodec.decode(PairingPayloadCodec.encode(original))
        assertEquals(original.deviceId, restored.deviceId)
        assertEquals(original.ephemeralUrl, restored.ephemeralUrl)
        assertEquals(original.challenge.base64, restored.challenge.base64)
        assertEquals(original.publicKeyFingerprint.hex, restored.publicKeyFingerprint.hex)
        assertEquals(original.expiresAtEpochMs, restored.expiresAtEpochMs)
    }

    @Test
    fun decode_ignores_unknown_properties_without_echoing_them() {
        val json = PairingPayloadCodec.encode(
            PairingPayload(
                deviceId = "phone-1",
                ephemeralUrl = "https://10.0.0.5:8942/pairing",
                challenge = challenge(0x55),
                publicKeyFingerprint = fingerprint(0x66),
                expiresAtEpochMs = 7L,
            )
        )
        val polluted = json.dropLast(1) + ",\"futureField\":{\"nested\":[1,2,3]}}"
        val restored = PairingPayloadCodec.decode(polluted)
        val reencoded = PairingPayloadCodec.encode(restored)
        assertEquals(json, reencoded)
        assertFalse(reencoded.contains("futureField"))
    }

    @Test
    fun decode_rejects_blank_text_and_non_object_roots() {
        assertEquals(PairingReasons.MALFORMED_JSON, decodeReason("   "))
        assertEquals(PairingReasons.MALFORMED_JSON, decodeReason("[]"))
        assertEquals(PairingReasons.MALFORMED_JSON, decodeReason("{\"schemaVersion\":1"))
    }

    @Test
    fun try_decode_folds_contract_errors_into_a_result() {
        val ok = PairingPayloadCodec.tryDecode(
            PairingPayloadCodec.encode(
                PairingPayload(
                    deviceId = "phone-1",
                    ephemeralUrl = "https://10.0.0.5:8942/pairing",
                    challenge = challenge(0x77),
                    publicKeyFingerprint = fingerprint(0x88),
                    expiresAtEpochMs = 9L,
                )
            )
        )
        assertTrue(ok is PairingPayloadCodec.DecodeResult.Ok)
        val failed = PairingPayloadCodec.tryDecode("{\"schemaVersion\":2}")
        assertTrue(failed is PairingPayloadCodec.DecodeResult.Failed)
        assertEquals(PairingReasons.UNSUPPORTED_VERSION, (failed as PairingPayloadCodec.DecodeResult.Failed).reason)
    }

    @Test
    fun remaining_milliseconds_never_goes_negative() {
        val payload = PairingPayload(expiresAtEpochMs = 1_000L)
        assertEquals(1_000L, payload.remainingMilliseconds(0L))
        assertEquals(0L, payload.remainingMilliseconds(1_000L))
        assertEquals(0L, payload.remainingMilliseconds(5_000L))
    }

    @Test
    fun validate_for_treats_the_boundary_as_expired() {
        val payload = PairingPayload(
            deviceId = "phone-1",
            ephemeralUrl = "https://10.0.0.5:8942/pairing",
            challenge = challenge(0x99),
            publicKeyFingerprint = fingerprint(0xAA),
            expiresAtEpochMs = 1_000L,
        )
        assertEquals("ok", validateReason(payload, 999L))
        assertEquals(PairingReasons.EXPIRED, validateReason(payload, 1_000L))
        assertEquals(PairingReasons.EXPIRED, validateReason(payload, 1_001L))
    }

    @Test
    fun url_validation_accepts_plain_http_and_https_only() {
        for (url in listOf("http://10.0.0.5:8080/pairing", "https://10.0.0.5:8942/pairing", "HTTPS://NAS.local:8942/x")) {
            val payload = PairingPayload(
                deviceId = "phone-1",
                ephemeralUrl = url,
                challenge = challenge(0x11),
                publicKeyFingerprint = fingerprint(0x22),
                expiresAtEpochMs = 1L,
            )
            payload.validateShape()
        }
        for (url in listOf("ftp://10.0.0.5/x", "10.0.0.5:8942/pairing", "https://user@10.0.0.5/x", "https://10.0.0.5/x#f")) {
            val payload = PairingPayload(
                deviceId = "phone-1",
                ephemeralUrl = url,
                challenge = challenge(0x11),
                publicKeyFingerprint = fingerprint(0x22),
                expiresAtEpochMs = 1L,
            )
            assertEquals(url, PairingReasons.BAD_URL, validateReason(payload, 0L))
        }
    }

    private fun decodeReason(json: String): String =
        try {
            PairingPayloadCodec.decode(json)
            "ok"
        } catch (e: PairingContractException) {
            e.reason
        }

    private fun validateReason(payload: PairingPayload, nowEpochMs: Long): String =
        try {
            payload.validateFor(nowEpochMs)
            "ok"
        } catch (e: PairingContractException) {
            e.reason
        }

    private fun challenge(seed: Int): PairingChallenge = PairingChallenge.fromBytes(ByteArray(32) { index -> (seed + index).toByte() })

    private fun fingerprint(seed: Int): PairingFingerprint = PairingFingerprint.fromBytes(ByteArray(32) { index -> (seed + index).toByte() })

    private inline fun <reified T : Throwable> assertFailsWithType(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("期望 " + T::class.java.simpleName + "，实际 " + e::class.java.simpleName, e)
        }
        throw AssertionError("期望 " + T::class.java.simpleName + "，但没有任何异常")
    }
}
