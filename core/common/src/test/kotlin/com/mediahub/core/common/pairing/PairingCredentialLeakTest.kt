package com.mediahub.core.common.pairing

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-J 安全回归：失败路径不得回显口令样式的内容。
 *
 * 断言面是"调用方能看到的全部输出"：异常 message / toString、结果对象字段、
 * 以及重新编码后的 JSON。配对与同步两个包目前都没有接入日志器，
 * 因此这里钉住的是异常与结果面；日志面列为未覆盖项（见 docs/android-security.md）。
 */
class PairingCredentialLeakTest {

    private val nowEpochMs = 1_700_000_000_000L
    private val secret = "hunter2-super-secret"
    private val challengeBase64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

    private fun payload(): PairingPayload = PairingPayload(
        schemaVersion = PairingPayload.CURRENT_SCHEMA_VERSION,
        deviceId = "device-windows",
        ephemeralUrl = "https://192.168.1.20:8942/pairing",
        challenge = PairingChallenge.parse(challengeBase64),
        publicKeyFingerprint = PairingFingerprint.fromBytes(ByteArray(32) { 0x11 }),
        expiresAtEpochMs = nowEpochMs + PairingPayload.LIFETIME_MILLISECONDS,
    )

    private fun base64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun payload_to_string_redacts_the_challenge_secret() {
        val text = payload().toString()
        assertTrue(text.contains("<redacted>"))
        assertFalse(text.contains(challengeBase64))
        assertFalse(text.contains(challengeBase64.removeSuffix("=")))
    }

    @Test
    fun rejected_challenge_never_echoes_its_value() {
        val bogus = Base64.getEncoder().encodeToString(secret.toByteArray())
        val caught = runCatching { PairingChallenge.parse(bogus) }.exceptionOrNull()
        assertTrue(caught is PairingContractException)
        val error = caught as PairingContractException
        assertEquals(PairingReasons.BAD_CHALLENGE, error.reason)
        assertFalse(error.message!!.contains(secret))
        assertFalse(error.toString().contains(secret))
        assertTrue(error.toString().contains(PairingReasons.BAD_CHALLENGE))
    }

    @Test
    fun url_credentials_are_rejected_without_being_echoed() {
        val json = "{\"schemaVersion\":1," +
            "\"deviceId\":\"device-windows\"," +
            "\"ephemeralUrl\":\"https://alice:" + secret + "@192.168.1.20:8942/pairing\"," +
            "\"challenge\":\"" + challengeBase64 + "\"," +
            "\"publicKeyFingerprint\":\"" + "a0".repeat(32) + "\"," +
            "\"expiresAtEpochMs\":" + (nowEpochMs + 120_000) + "}"

        val caught = runCatching { PairingPayloadCodec.decode(json) }.exceptionOrNull()
        val error = caught as PairingContractException
        assertEquals(PairingReasons.BAD_URL, error.reason)
        assertFalse(error.message!!.contains(secret))
        assertFalse(error.toString().contains(secret))

        val result = PairingPayloadCodec.tryDecode(json)
        assertTrue(result is PairingPayloadCodec.DecodeResult.Failed)
        val failed = result as PairingPayloadCodec.DecodeResult.Failed
        assertEquals(PairingReasons.BAD_URL, failed.reason)
        assertFalse(failed.message!!.contains(secret))
        assertFalse(failed.toString().contains(secret))
    }

    @Test
    fun pairing_code_failure_carries_only_a_reason_code() {
        val json = "{\"schemaVersion\":1," +
            "\"deviceId\":\"device-windows\"," +
            "\"ephemeralUrl\":\"https://alice:" + secret + "@192.168.1.20:8942/pairing\"," +
            "\"challenge\":\"" + challengeBase64 + "\"," +
            "\"publicKeyFingerprint\":\"" + "a0".repeat(32) + "\"," +
            "\"expiresAtEpochMs\":" + (nowEpochMs + 120_000) + "}"

        val result = PairingCode.decode(base64Url(json), nowEpochMs)
        assertNull(result.payload)
        assertEquals(PairingReasons.BAD_URL, result.reasonOrOk)
        assertFalse(result.reasonOrOk.contains(secret))
        assertFalse(result.toString().contains(secret))
    }

    @Test
    fun pairing_link_failure_does_not_echo_the_code() {
        val link = PairingLink.PREFIX + "alice:" + secret + "@host"
        val result = PairingLink.parse(link, nowEpochMs)
        assertNull(result.payload)
        assertEquals(PairingCodeReasons.NOT_BASE64URL, result.reasonOrOk)
        assertFalse(result.reasonOrOk.contains(secret))
        assertFalse(result.toString().contains(secret))
    }

    @Test
    fun fingerprint_failure_message_contains_only_the_length() {
        val bogus = secret + "z"
        val caught = runCatching { PairingFingerprint.parse(bogus) }.exceptionOrNull()
        val error = caught as PairingContractException
        assertEquals(PairingReasons.BAD_FINGERPRINT, error.reason)
        assertFalse(error.message!!.contains(secret))
        assertFalse(error.message!!.contains(bogus))
        assertTrue(error.message!!.contains(bogus.length.toString()))
    }
}
