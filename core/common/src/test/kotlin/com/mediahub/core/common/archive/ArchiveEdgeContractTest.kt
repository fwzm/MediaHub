package com.mediahub.core.common.archive

import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncContractException
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.WireText
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨语言边界契约：空白、大小写、孤立代理项、JSON 畸形（W6-67）。
 *
 * 资源 archive/windows-archive-vectors.json 的 edges 段由 D:/mh-verify/archive-vectors（临时 net10.0
 * console 工程，不入库，ProjectReference 指向 D:/mh-w6/windows/src/MediaHub.Sync）直接调 Windows 权威
 * 实现生成。本类不自己算期望值：每条断言都把 Windows 亲口给出的答案与 Kotlin 的答案逐条比对，
 * 两侧都必须等于同一个向量值。
 *
 * 这些用例存在的理由：W6-62/63/64/65 的互操作对照都建立在「同一条坏输入两端报同一个码」上，而
 * 空白字符集、大小写折叠、UTF-16 代理项这三件事在 JVM 与 .NET 里默认规则不同 —— 不钉住就会在
 * 用户真实数据上分叉。
 */
class ArchiveEdgeContractTest {

    private val vectors: JsonObject by lazy {
        Json.parseToJsonElement(resource("windows-archive-vectors.json")).jsonObject
    }

    private val edges: JsonObject by lazy { vectors.getValue("edges").jsonObject }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/archive/" + name)) { "缺少测试资源 /archive/" + name }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun textOf(value: JsonElement): String = value.jsonPrimitive.content

    /** 向量的 utf16 是码元数组而不是码点：孤立代理项只有按码元构造才不会在构造时被替换。 */
    private fun utf16Of(value: JsonElement): String {
        val units = value.jsonArray.map { it.jsonPrimitive.int }
        val chars = CharArray(units.size) { Char(units[it]) }
        return String(chars)
    }

    private fun arrayOf(name: String): JsonArray = edges.getValue(name).jsonArray

    private fun entries(name: String): List<JsonObject> = arrayOf(name).map { it.jsonObject }

    private fun archiveBytes(): ByteArray =
        Base64.getDecoder().decode(textOf(vectors.getValue("archive").jsonObject.getValue("base64")))

    private fun canonicalEnvelope(): SourceSyncEnvelope {
        val archive = archiveBytes()
        val (envelope, reason) = ArchiveReader.tryRead(archive, textOf(vectors.getValue("passphrase")))
        assertNull(reason)
        assertNotNull(envelope)
        return envelope!!
    }

    // ------------------------------------------------------------- 空白与大小写（WireText）

    @Test
    fun the_whitespace_table_is_the_unicode_white_space_property_used_by_dotnet() {
        val expected = edges.getValue("whitespaceCodePoints").jsonArray.map { it.jsonPrimitive.int }.toSet()
        assertEquals(25, expected.size)
        assertEquals(WireText.WHITESPACE_CODE_POINTS.toSet(), expected)
        for (codePoint in expected) {
            assertTrue("U+" + codePoint.toString(16) + " 应算空白", WireText.isWhitespace(Char(codePoint)))
        }
        // 两端刻意都不算空白的四个：记录分隔符 U+001C/U+001F、零宽空格 U+200B、BOM U+FEFF。
        for (codePoint in listOf(0x001C, 0x001F, 0x200B, 0xFEFF, 0x00AD)) {
            assertFalse("U+" + codePoint.toString(16) + " 不应算空白", WireText.isWhitespace(Char(codePoint)))
        }
    }

    @Test
    fun is_blank_and_trim_agree_with_the_windows_answers_for_every_candidate() {
        for (entry in entries("blankCandidates")) {
            val name = textOf(entry.getValue("name"))
            val text = utf16Of(entry.getValue("utf16"))
            assertEquals(name, entry.getValue("blank").jsonPrimitive.content.toBoolean(), WireText.isBlank(text))
            assertEquals(name, utf16Of(entry.getValue("trimmed")), WireText.trim(text))
        }
    }

    @Test
    fun ascii_case_folding_never_folds_a_non_ascii_lookalike() {
        for (entry in entries("caseFoldPairs")) {
            val name = textOf(entry.getValue("name"))
            val left = utf16Of(entry.getValue("left"))
            val right = utf16Of(entry.getValue("right"))
            assertEquals(name, entry.getValue("equal").jsonPrimitive.content.toBoolean(), WireText.equalsAsciiIgnoreCase(left, right))
        }
        // 这一条是 JVM equals(ignoreCase = true) 与 .NET OrdinalIgnoreCase 的真实分歧。
        assertTrue("长 s 不得折成 ascii s", WireText.equalsAsciiIgnoreCase("pa" + Char(0x017F) + "sword", "pa" + Char(0x017F) + "sword"))
        assertFalse(WireText.equalsAsciiIgnoreCase("pa" + Char(0x017F) + "sword1234", "password1234"))
    }

    @Test
    fun lone_surrogates_are_detected_by_code_unit_not_by_character_count() {
        for (entry in entries("loneSurrogateCandidates")) {
            val name = textOf(entry.getValue("name"))
            val text = utf16Of(entry.getValue("utf16"))
            assertEquals(name, entry.getValue("lone").jsonPrimitive.content.toBoolean(), WireText.hasLoneSurrogate(text))
        }
        assertFalse("合法代理对不是孤立代理项", WireText.hasLoneSurrogate(String(charArrayOf(Char(0xD83D), Char(0xDD12)))))
    }

    // ------------------------------------------------------------- 口令策略

    @Test
    fun the_passphrase_policy_gives_the_windows_answer_for_every_candidate() {
        for (entry in entries("passphraseCandidates")) {
            val name = textOf(entry.getValue("name"))
            val passphrase = utf16Of(entry.getValue("utf16"))
            val expected = entry.getValue("reason").let { if (it is JsonNull) null else textOf(it) }
            assertEquals(name, expected, ArchivePassphrase.tryValidate(passphrase))
        }
    }

    @Test
    fun a_lone_surrogate_passphrase_is_rejected_before_pbkdf2_sees_it() {
        val tail = "correct horse battery staple" + Char(0xD800)
        assertEquals(ArchiveError.PASSPHRASE_TOO_WEAK, ArchivePassphrase.tryValidate(tail))
        // 派生之前就被挡住：写侧只报稳定码，不会走到编码器。
        val thrown = runCatching { ArchiveWriter.write(canonicalEnvelope(), tail) }.exceptionOrNull()
        assertTrue("应为 ArchiveException，实际 " + thrown, thrown is ArchiveException)
        assertEquals(ArchiveError.PASSPHRASE_TOO_WEAK, (thrown as ArchiveException).reason)
    }

    // ------------------------------------------------------------- kdf 名与头部

    @Test
    fun kdf_names_are_matched_with_ascii_folding_and_the_unicode_whitespace_set() {
        for (entry in entries("kdfNameCandidates")) {
            val name = textOf(entry.getValue("name"))
            val candidate = utf16Of(entry.getValue("utf16"))
            assertEquals(name, entry.getValue("accepted").jsonPrimitive.content.toBoolean(), ArchiveKdf.tryParse(candidate))
        }
    }

    @Test
    fun header_parsing_and_kdf_acceptance_agree_with_the_windows_answers() {
        for (entry in entries("headerVariants")) {
            val name = textOf(entry.getValue("name"))
            val bytes = Base64.getDecoder().decode(textOf(entry.getValue("headerBase64")))
            val expectedParses = entry.getValue("parses").jsonPrimitive.content.toBoolean()
            val parsed = ArchiveHeaderCodec.tryParse(bytes)
            assertEquals(name, expectedParses, parsed != null)
            val kdfAccepted = entry.getValue("kdfAccepted")
            if (parsed != null && kdfAccepted !is JsonNull) {
                assertEquals(name, kdfAccepted.jsonPrimitive.content.toBoolean(), ArchiveKdf.tryParse(parsed.kdf))
            }
            val version = entry.getValue("version")
            if (parsed != null && version !is JsonNull) {
                assertEquals(name, version.jsonPrimitive.int, parsed.version)
            }
        }
    }

    // ------------------------------------------------------------- 信封与归档

    @Test
    fun envelope_decoding_reports_the_windows_reason_for_every_variant() {
        for (entry in entries("envelopeVariants")) {
            val name = textOf(entry.getValue("name"))
            val document = textOf(entry.getValue("document"))
            val expected = entry.getValue("reason").let { if (it is JsonNull) null else textOf(it) }
            when (val result = SourceSyncCodec.tryDecode(document)) {
                is SourceSyncCodec.DecodeResult.Ok -> assertNull(name, expected)
                is SourceSyncCodec.DecodeResult.Failed -> assertEquals(name, expected, result.reason)
            }
        }
    }

    @Test
    fun envelope_writing_rejects_the_same_documents_the_windows_writer_rejects() {
        val base = canonicalEnvelope()
        for (entry in entries("envelopeWriteRejections")) {
            val name = textOf(entry.getValue("name"))
            val expected = entry.getValue("writeReason").let { if (it is JsonNull) null else textOf(it) }
            val candidate = when (name) {
                "device-id-lone-surrogate" -> base.copy(deviceId = "a" + Char(0xD800) + "b")
                "device-id-next-line-only" -> base.copy(deviceId = Char(0x0085).toString())
                "display-name-lone-surrogate" -> base.copy(
                    sources = listOf(
                        base.sources.first().copy(displayName = "a" + Char(0xD800) + "b"),
                    ),
                )
                else -> base
            }
            val thrown = runCatching { SourceSyncCodec.encode(candidate) }.exceptionOrNull()
            if (expected == null) {
                assertNull(name, thrown)
            } else {
                assertTrue(name + " 应抛合同异常，实际 " + thrown, thrown is SourceSyncContractException)
                assertEquals(name, expected, (thrown as SourceSyncContractException).reason)
            }
        }
    }

    @Test
    fun archive_reading_and_writing_agree_on_every_passphrase_case() {
        val archive = archiveBytes()
        val canonical = canonicalEnvelope()
        for (entry in entries("archivePassphraseCases")) {
            val name = textOf(entry.getValue("name"))
            val passphrase = utf16Of(entry.getValue("passphraseUtf16"))
            val expectedRead = entry.getValue("readReason").let { if (it is JsonNull) null else textOf(it) }
            val expectedWrite = entry.getValue("writeReason").let { if (it is JsonNull) null else textOf(it) }

            val (_, readReason) = ArchiveReader.tryRead(archive, passphrase)
            assertEquals(name + "（读）", expectedRead, readReason)

            val thrown = runCatching { ArchiveWriter.write(canonical, passphrase) }.exceptionOrNull()
            if (expectedWrite == null) {
                assertNull(name + "（写）", thrown)
            } else {
                assertTrue(name + "（写）应为 ArchiveException，实际 " + thrown, thrown is ArchiveException)
                assertEquals(name + "（写）", expectedWrite, (thrown as ArchiveException).reason)
            }
        }
    }
}
