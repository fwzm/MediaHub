package com.mediahub.core.common.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 配对卡片与自报身份的跨语言契约（W6-71）。
 *
 * 所有期望值都来自 Windows 权威实现（MediaHub.Sync.PairingPeerCard / PairingSelfIdentity）跑出的
 * 黄金向量 windows-pairing-vectors.json 的 peerCard 段，不手写期望：同一份 JSON 也由 C# 的
 * PairingPeerCardContractTests 消费，任一端偏离都会有一套红。
 *
 * 钉住的东西：卡片写出的逐字节 JSON（含 Utf8JsonWriter 的转义规则）、读端的 6 条拒绝码与判定序、
 * 稳定指纹的派生式、以及「卡片 -> 登记输入」的字段映射。
 */
class PairingPeerCardContractTest {

    private val root: JsonObject = Json.parseToJsonElement(readResource()).jsonObject

    private val peerCard: JsonObject = root.getValue("peerCard").jsonObject

    private fun readResource(): String {
        val stream = javaClass.getResourceAsStream(RESOURCE_PATH)
        requireNotNull(stream) { "找不到配对黄金向量资源 " + RESOURCE_PATH }
        return stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    }

    private fun arrayOf(name: String): JsonArray = peerCard.getValue(name).jsonArray

    private fun str(element: JsonObject, name: String): String = element.getValue(name).jsonPrimitive.content

    private fun intOf(element: JsonObject, name: String): Int = element.getValue(name).jsonPrimitive.int

    private fun longOf(element: JsonObject, name: String): Long = element.getValue(name).jsonPrimitive.long

    @Test
    fun reason_codes_and_limits_match_the_windows_contract() {
        val reasons = arrayOf("reasons").map { it.jsonPrimitive.content }
        assertEquals(PairingPeerCardReasons.ALL, reasons)
        assertEquals(6, PairingPeerCardReasons.ALL.size)
        assertEquals(intOf(peerCard, "currentSchemaVersion"), PairingPeerCard.CURRENT_SCHEMA_VERSION)
        assertEquals(intOf(peerCard, "maximumTextLength"), PairingPeerCard.MAXIMUM_TEXT_LENGTH)
        assertEquals(intOf(peerCard, "maximumLength"), PairingPeerCardCodec.MAXIMUM_LENGTH)
        assertEquals(str(peerCard, "derivationPrefix"), PairingSelfIdentity.DERIVATION_PREFIX)
    }

    @Test
    fun serialized_cards_match_the_windows_bytes() {
        val cases = arrayOf("serializationCases").map { it.jsonObject }
        assertTrue(cases.size >= 6)
        for (entry in cases) {
            val name = str(entry, "name")
            val card = PairingPeerCard(
                deviceId = str(entry, "deviceId"),
                deviceFingerprint = str(entry, "deviceFingerprint"),
                displayName = str(entry, "displayName"),
                sourceCount = intOf(entry, "sourceCount"),
            )
            val encoded = PairingPeerCardCodec.serialize(card)
            assertEquals(name + " (json)", str(entry, "json"), encoded)
            val bytes = encoded.toByteArray(StandardCharsets.UTF_8)
            assertEquals(name + " (byte length)", intOf(entry, "utf8ByteLength"), bytes.size)
            assertEquals(name + " (sha256)", str(entry, "sha256"), sha256(bytes))
        }
    }

    @Test
    fun decoded_cards_match_the_windows_contract() {
        val cases = arrayOf("decodeCases").map { it.jsonObject }
        assertTrue(cases.size >= 20)
        val seenReasons = mutableSetOf<String>()
        for (entry in cases) {
            val name = str(entry, "name")
            val expectedAccepted = str(entry, "accepted").toBoolean()
            val result = PairingPeerCardCodec.tryDeserialize(str(entry, "json"))
            if (!expectedAccepted) {
                val failed = result as? PairingPeerCardCodec.DecodeResult.Failed
                requireNotNull(failed) { name + " 应该被拒绝" }
                assertEquals(name + " (reason)", str(entry, "reason"), failed.reason)
                seenReasons.add(failed.reason)
                continue;
            }
            val ok = result as? PairingPeerCardCodec.DecodeResult.Ok
            requireNotNull(ok) { name + " 应该被接受" }
            val expected = entry.getValue("card").jsonObject
            assertEquals(name + " (deviceId)", str(expected, "deviceId"), ok.card.deviceId)
            assertEquals(name + " (fingerprint)", str(expected, "deviceFingerprint"), ok.card.deviceFingerprint)
            assertEquals(name + " (displayName)", str(expected, "displayName"), ok.card.displayName)
            assertEquals(name + " (sourceCount)", intOf(expected, "sourceCount"), ok.card.sourceCount)
            assertEquals(name + " (schemaVersion)", PairingPeerCard.CURRENT_SCHEMA_VERSION, ok.card.schemaVersion)
        }
        // 6 条码必须都被向量覆盖到，缺一条就说明有一种拒绝没人钉。
        assertEquals(PairingPeerCardReasons.ALL.toSet(), seenReasons)
    }

    @Test
    fun self_identity_cases_match_the_windows_derivation() {
        val cases = arrayOf("selfIdentityCases").map { it.jsonObject }
        assertTrue(cases.size >= 5)
        for (entry in cases) {
            val name = str(entry, "name")
            val deviceId = str(entry, "deviceId")
            assertEquals(name + " (derived)", str(entry, "fingerprintHex"), PairingSelfIdentity.deriveFingerprint(deviceId).hex)
            val identity = PairingSelfIdentity.create(deviceId, "  安卓平板  ", -2)
            assertEquals(name + " (trimmed id)", str(entry, "trimmedDeviceId"), identity.deviceId)
            assertEquals(name + " (displayName trimmed)", str(entry, "displayName"), identity.displayName)
            assertEquals(name + " (sourceCount clamped)", intOf(entry, "sourceCount"), identity.sourceCount)
            assertEquals(name + " (card json)", str(entry, "cardJson"), PairingPeerCardCodec.serialize(identity.toCard()))
        }
    }

    @Test
    fun blank_identity_is_rejected_like_the_windows_contract() {
        assertTrue(str(peerCard, "rejectsBlankIdentity").toBoolean())
        assertThrows(IllegalArgumentException::class.java) { PairingSelfIdentity.create("   ") }
        assertThrows(IllegalArgumentException::class.java) { PairingSelfIdentity.deriveFingerprint("") }
    }

    @Test
    fun observation_case_carries_the_caller_clock() {
        val expected = peerCard.getValue("observationCase").jsonObject
        val card = PairingPeerCard(
            deviceId = str(expected, "deviceId"),
            deviceFingerprint = str(expected, "fingerprint"),
            displayName = str(expected, "displayName"),
            sourceCount = intOf(expected, "sourceCount"),
        )
        val observation = card.toObservation(longOf(expected, "nowEpochMs"))
        assertEquals(str(expected, "deviceId"), observation.deviceId)
        assertEquals(str(expected, "fingerprint"), observation.fingerprint)
        assertEquals(str(expected, "displayName"), observation.displayName)
        assertEquals(intOf(expected, "sourceCount"), observation.sourceCount)
        assertEquals(longOf(expected, "nowEpochMs"), observation.nowEpochMs)
    }

    @Test
    fun decode_never_throws_on_hostile_text() {
        val hostile = listOf(
            null,
            "",
            "   ",
            "{",
            "[]",
            "{\"schemaVersion\":1,\"deviceId\":\"d\",\"deviceFingerprint\":\"zz\"}",
            "{\"schemaVersion\":1,\"deviceId\":\"d\",\"deviceFingerprint\":\"" + "a".repeat(64) + "\",\"displayName\":\"\\uD800\"}",
        )
        for (json in hostile) {
            val result = PairingPeerCardCodec.tryDeserialize(json)
            assertTrue("应当被拒绝：" + json, result is PairingPeerCardCodec.DecodeResult.Failed)
        }
        val truncation = PairingPeerCardCodec.tryDeserialize("{\"schemaVersion\":1,\"deviceId\":\"d\",\"deviceFingerprint\":\"" + "a".repeat(64) + "\",\"displayName\":\"" + "x".repeat(PairingPeerCard.MAXIMUM_TEXT_LENGTH + 5) + "\"}")
        val ok = truncation as? PairingPeerCardCodec.DecodeResult.Ok
        requireNotNull(ok) { "过长名字应当被截断而不是拒绝" }
        assertEquals(PairingPeerCard.MAXIMUM_TEXT_LENGTH, ok.card.displayName.length)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX_LOWER[value ushr 4])
            builder.append(HEX_LOWER[value and 0x0F])
        }
        return builder.toString()
    }

    private companion object {
        const val RESOURCE_PATH: String = "/pairing/windows-pairing-vectors.json"
        const val HEX_LOWER: String = "0123456789abcdef"
    }
}
