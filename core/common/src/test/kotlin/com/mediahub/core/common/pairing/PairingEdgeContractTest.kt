package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * 配对侧文本边界契约（W6-69）：空白集、ASCII 大小写折叠、前缀/比较、未配对代理项，
 * 以及链接与载荷解码在这些边界上的稳定码。
 *
 * 所有期望值都来自 Windows 权威实现跑出的黄金向量 windows-pairing-vectors.json 的 textEdges 段，
 * 不手写期望：同一份 JSON 也由 C# 的 PairingEdgeContractTests 消费，任一端偏离都会有一套红。
 */
class PairingEdgeContractTest {

    private val root: JsonObject = Json.parseToJsonElement(readResource()).jsonObject

    private val edges: JsonObject = root.getValue("textEdges").jsonObject

    private fun readResource(): String {
        val stream = javaClass.getResourceAsStream(RESOURCE_PATH)
        requireNotNull(stream) { "找不到配对黄金向量资源 " + RESOURCE_PATH }
        return stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    }

    private fun entries(name: String): JsonArray = edges.getValue(name).jsonArray

    /** 向量把文本存成 UTF-16 码元数组，孤立代理项才能原样往返。 */
    private fun utf16Of(element: JsonElement): String {
        val units = element.jsonArray.map { it.jsonPrimitive.int }
        val characters = CharArray(units.size) { units[it].toChar() }
        return String(characters)
    }

    private fun textOf(entry: JsonObject, name: String): String = utf16Of(entry.getValue(name))

    private fun nameOf(entry: JsonObject): String = entry.getValue("name").jsonPrimitive.content

    // ------------------------------------------------------------ 空白与大小写

    @Test
    fun whitespace_table_matches_the_vectors_and_excludes_the_jvm_extras() {
        val expected = entries("whitespaceCodePoints").map { it.jsonPrimitive.int }
        assertEquals(25, expected.size)
        assertEquals(expected, WireText.WHITESPACE_CODE_POINTS.toList())

        // JVM 默认空白集含 U+001C–U+001F 且不含 U+0085，这里必须按显式表判，不能跟运行时走。
        for (notWhitespace in intArrayOf(0x001C, 0x001F, 0x200B, 0xFEFF, 0x00AD)) {
            assertFalse("U+" + notWhitespace.toString(16), WireText.isWhitespace(notWhitespace.toChar()))
        }
        assertTrue(WireText.isWhitespace(0x0085.toChar()))
        assertTrue(WireText.isWhitespace(0x3000.toChar()))
    }

    @Test
    fun is_blank_and_trim_agree_with_every_candidate() {
        for (entry in entries("blankCandidates").map { it.jsonObject }) {
            val name = nameOf(entry)
            val text = textOf(entry, "utf16")
            val blank = entry.getValue("blank").jsonPrimitive.content.toBoolean()
            assertEquals(name + " (blank)", blank, WireText.isBlank(text))
            assertEquals(name + " (trim)", textOf(entry, "trimmed"), WireText.trim(text))
        }
    }

    @Test
    fun ascii_case_folding_never_folds_a_non_ascii_lookalike() {
        for (entry in entries("caseFoldPairs").map { it.jsonObject }) {
            val name = nameOf(entry)
            val left = textOf(entry, "left")
            val right = textOf(entry, "right")
            val expected = entry.getValue("equal").jsonPrimitive.content.toBoolean()
            assertEquals(name, expected, WireText.equalsAsciiIgnoreCase(left, right))
        }
    }

    @Test
    fun ascii_case_transforms_only_touch_ascii_letters() {
        for (entry in entries("asciiCaseTransforms").map { it.jsonObject }) {
            val name = nameOf(entry)
            val text = textOf(entry, "utf16")
            assertEquals(name + " (upper)", textOf(entry, "upper"), WireText.uppercaseAscii(text))
            assertEquals(name + " (lower)", textOf(entry, "lower"), WireText.lowercaseAscii(text))
        }
    }

    @Test
    fun prefix_test_folds_ascii_only() {
        for (entry in entries("prefixPairs").map { it.jsonObject }) {
            val name = nameOf(entry)
            val text = textOf(entry, "value")
            val prefix = textOf(entry, "prefix")
            val expected = entry.getValue("result").jsonPrimitive.content.toBoolean()
            assertEquals(name, expected, WireText.startsWithAsciiIgnoreCase(text, prefix))
        }
    }

    @Test
    fun comparison_is_a_total_order_that_folds_ascii_only() {
        for (entry in entries("comparePairs").map { it.jsonObject }) {
            val name = nameOf(entry)
            val left = textOf(entry, "left")
            val right = textOf(entry, "right")
            val expected = entry.getValue("sign").jsonPrimitive.int
            val forward = WireText.compareAsciiIgnoreCase(left, right)
            assertEquals(name, expected, forward.coerceIn(-1, 1))
            assertTrue(name + " (antisymmetric)", WireText.compareAsciiIgnoreCase(right, left) == -forward)
        }
    }

    @Test
    fun lone_surrogate_detection_matches_every_candidate() {
        for (entry in entries("loneSurrogateCandidates").map { it.jsonObject }) {
            val name = nameOf(entry)
            val expected = entry.getValue("lone").jsonPrimitive.content.toBoolean()
            assertEquals(name, expected, WireText.hasLoneSurrogate(textOf(entry, "utf16")))
        }
    }

    // ------------------------------------------------------------ 链接与载荷

    @Test
    fun link_parsing_reports_the_windows_reason_for_every_candidate() {
        for (entry in entries("linkCandidates").map { it.jsonObject }) {
            val name = nameOf(entry)
            val link = textOf(entry, "link")
            val expected = entry.getValue("reason").jsonPrimitive.content
            val now = entry.getValue("nowEpochMs").jsonPrimitive.long
            assertEquals(name, expected, PairingLink.parse(link, now).reasonOrOk)
        }
    }

    @Test
    fun payload_decoding_reports_the_windows_reason_and_reads_the_same_value() {
        for (entry in entries("payloadCandidates").map { it.jsonObject }) {
            val name = nameOf(entry)
            val field = entry.getValue("field").jsonPrimitive.content
            val json = entry.getValue("json").jsonPrimitive.content
            val expectedReason = entry.getValue("reason").jsonPrimitive.content

            val payload = try {
                PairingPayloadCodec.decode(json)
            } catch (e: PairingContractException) {
                assertEquals(name, expectedReason, e.reason)
                null
            }
            if (payload == null) continue

            assertEquals(name, "ok", expectedReason)
            val afterRead = entry.getValue("afterRead")
            if (afterRead is JsonNull) continue
            assertEquals(name + " (" + field + ")", utf16Of(afterRead), readField(payload, field))
        }
    }

    @Test
    fun documents_with_a_lone_surrogate_are_refused_as_malformed_json() {
        for (entry in entries("loneSurrogateDocuments").map { it.jsonObject }) {
            val name = nameOf(entry)
            val json = entry.getValue("json").jsonPrimitive.content
            val expected = entry.getValue("reason").jsonPrimitive.content
            val reason = try {
                PairingPayloadCodec.decode(json)
                "ok"
            } catch (e: PairingContractException) {
                e.reason
            }
            assertEquals(name, expected, reason)
            if (expected == PairingReasons.MALFORMED_JSON) {
                assertEquals(name + " (stable code)", PairingReasons.MALFORMED_JSON, reason)
            } else {
                assertEquals(name + " (paired surrogate stays valid)", "ok", reason)
            }
        }
    }

    private fun readField(payload: PairingPayload, field: String): String = when (field) {
        "deviceId" -> payload.deviceId
        "ephemeralUrl" -> payload.ephemeralUrl
        "challenge" -> payload.challenge.base64
        "publicKeyFingerprint" -> payload.publicKeyFingerprint.hex
        else -> throw IllegalArgumentException("未知字段 " + field)
    }

    private companion object {
        const val RESOURCE_PATH: String = "/pairing/windows-pairing-vectors.json"
    }
}