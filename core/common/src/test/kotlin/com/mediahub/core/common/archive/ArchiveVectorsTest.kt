package com.mediahub.core.common.archive

import com.mediahub.core.common.sync.SourceSyncCodec
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与 Windows 真实归档的互操作对照（不是手写夹具）。
 *
 * 资源 archive/windows-archive-vectors.json 由 D:/mh-verify/archive-vectors（临时 net10.0 console
 * 工程，不入库，ProjectReference 指向 D:/mh-w6/windows/src/MediaHub.Sync/MediaHub.Sync.csproj）
 * 直接调 Windows 权威实现生成：
 * - kdf：三个派生密钥（口令 + Salt、另一口令 + SecondSalt、中文口令 + SecondSalt）；
 * - header.canonicalJson：Windows 序列化出的头部原字节（161 B，同时是 AES-GCM 的 AAD）；
 * - archive.hex / sha256：固定 salt+nonce 加密 device-a 信封得到的完整归档（1282 B）；
 * - errors：19 条坏归档，每条带 Windows 亲口给出的稳定码与 headerParses；
 * - writerCases：9 条写侧判定（含「口令先于凭据、凭据先于迭代数」的次序证据）；
 * - passphrases：合法口令 4 条与非法口令 9 条。
 *
 * 对照口径：码名逐字相等；密钥、头部、归档**字节逐字节相等**。
 *
 * 一个必须记住的差异：两端信封编码器对非 ASCII 的转义策略不同（Windows 写 \uXXXX，Kotlin 写原字符，
 * 见 WindowsGoldenInteropTest）。所以「归档逐字节复算」必须用向量里的 payloadJson 原字节，
 * 不能用 Kotlin 重新 encode 的结果；读回来的信封也只能按结构化比较。
 */
class ArchiveVectorsTest {

    private val vectors: JsonObject by lazy {
        Json.parseToJsonElement(resource("windows-archive-vectors.json")).jsonObject
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/archive/" + name)) { "缺少测试资源 /archive/" + name }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun textOf(value: JsonElement): String = value.jsonPrimitive.content

    private fun kdf(): JsonObject = vectors.getValue("kdf").jsonObject

    private fun headerRecord(): JsonObject = vectors.getValue("header").jsonObject

    private fun archiveRecord(): JsonObject = vectors.getValue("archive").jsonObject

    private fun payloadBytes(): ByteArray =
        vectors.getValue("envelope").jsonObject.getValue("payloadJson").jsonPrimitive.content
            .toByteArray(Charsets.UTF_8)

    private fun base64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun hex(text: String): ByteArray {
        val clean = text.trim()
        require(clean.length % 2 == 0) { "hex 长度必须是偶数，实际 " + clean.length }
        val out = ByteArray(clean.length / 2)
        for (index in out.indices) {
            val high = Character.digit(clean[index * 2], 16)
            val low = Character.digit(clean[index * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "不是合法 hex：" + clean.substring(index * 2, index * 2 + 2) }
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun toHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(digits[value ushr 4])
            builder.append(digits[value and 0x0F])
        }
        return builder.toString()
    }

    private fun sha256(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    // ------------------------------------------------------------------ 常量

    @Test
    fun the_kdf_and_header_constants_match_the_windows_contract() {
        val kdf = kdf()
        assertEquals("pbkdf2-sha256", textOf(kdf.getValue("name")))
        assertEquals(ArchiveKdf.PBKDF2_SHA256_NAME, textOf(kdf.getValue("name")))
        assertEquals(ArchiveKdf.SALT_SIZE, kdf.getValue("saltSize").jsonPrimitive.int)
        assertEquals(ArchiveKdf.KEY_SIZE, kdf.getValue("keySize").jsonPrimitive.int)
        assertEquals(ArchiveKdf.MIN_ITERATIONS, kdf.getValue("minIterations").jsonPrimitive.int)
        assertEquals(ArchiveKdf.DEFAULT_ITERATIONS, kdf.getValue("defaultIterations").jsonPrimitive.int)
        assertEquals(600_000, ArchiveKdf.MIN_ITERATIONS)
        assertEquals(16, ArchiveKdf.SALT_SIZE)
        assertEquals(32, ArchiveKdf.KEY_SIZE)

        val header = headerRecord()
        assertEquals(ArchiveHeader.CURRENT_MAGIC, textOf(header.getValue("magic")))
        assertEquals(ArchiveHeader.CURRENT_VERSION, header.getValue("version").jsonPrimitive.int)
        assertEquals(ArchiveHeader.NONCE_SIZE, header.getValue("nonceSize").jsonPrimitive.int)
        assertEquals(ArchiveHeader.TAG_SIZE, header.getValue("tagSize").jsonPrimitive.int)
        assertEquals(ArchiveHeader.MAX_HEADER_BYTES, header.getValue("maxHeaderBytes").jsonPrimitive.int)
        assertEquals(ArchiveHeader.MAX_PAYLOAD_BYTES, header.getValue("maxPayloadBytes").jsonPrimitive.int)
        assertEquals(8 * 1024 * 1024, ArchiveHeader.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun the_twelve_stable_codes_are_identical_and_in_the_same_order_as_windows() {
        // 12 个基础码（两个带参数的只列前缀），顺序必须与 Windows 常量声明顺序一致。
        assertEquals(
            listOf(
                "magic-mismatch",
                "corrupt-header",
                "unsupported-version",
                "unknown-kdf",
                "invalid-iterations",
                "passphrase-empty",
                "passphrase-too-weak",
                "secrets-not-allowed",
                "payload-too-large",
                "corrupt-ciphertext",
                "wrong-passphrase-or-tampered",
                "malformed-payload",
            ),
            ArchiveError.ALL,
        )
        assertEquals(12, ArchiveError.ALL.size)
        assertEquals("unsupported-version:2", ArchiveError.unsupportedVersion(2))
        assertEquals("unknown-kdf:argon2id", ArchiveError.unknownKdf("argon2id"))
    }

    // ------------------------------------------------------------------ kdf 黄金向量

    @Test
    fun the_three_frozen_pbkdf2_keys_reproduce_byte_for_byte() {
        val kdf = kdf()
        // keyHex 用 Salt；secondKeyHex 与 chineseKeyHex 用 SecondSalt（生成器源码如此，勿混）。
        val salt = base64(textOf(kdf.getValue("saltBase64")))
        val secondSalt = base64(textOf(kdf.getValue("secondSaltBase64")))
        val iterations = kdf.getValue("iterations").jsonPrimitive.int
        assertEquals(
            textOf(kdf.getValue("keyHex")),
            toHex(ArchiveKdf.deriveKey(textOf(vectors.getValue("passphrase")), salt, iterations)),
        )
        assertEquals(
            textOf(kdf.getValue("secondKeyHex")),
            toHex(ArchiveKdf.deriveKey(textOf(vectors.getValue("otherPassphrase")), secondSalt, iterations)),
        )
        assertEquals(
            textOf(kdf.getValue("chineseKeyHex")),
            toHex(ArchiveKdf.deriveKey(textOf(kdf.getValue("chinesePassphrase")), secondSalt, iterations)),
        )
    }

    @Test
    fun the_kdf_parser_rejects_everything_but_the_one_frozen_name() {
        assertTrue(ArchiveKdf.tryParse("pbkdf2-sha256"))
        assertTrue("大小写与首尾空白按 Windows 的 trim + OrdinalIgnoreCase 处理", ArchiveKdf.tryParse("  PBKDF2-SHA256  "))
        assertFalse("未知 kdf 不得回落默认值", ArchiveKdf.tryParse("argon2id"))
        assertFalse(ArchiveKdf.tryParse(""))
        assertFalse(ArchiveKdf.tryParse(null))
    }

    // ------------------------------------------------------------------ 头部逐字节

    @Test
    fun the_header_serializer_reproduces_the_windows_bytes_exactly() {
        val header = headerRecord()
        val rebuilt = ArchiveHeaderCodec.serialize(
            ArchiveHeader(
                version = header.getValue("version").jsonPrimitive.int,
                kdf = textOf(header.getValue("kdf")),
                iterations = header.getValue("iterations").jsonPrimitive.int,
                saltBase64 = textOf(header.getValue("saltBase64")),
                nonceBase64 = textOf(header.getValue("nonceBase64")),
                createdAtEpochMs = header.getValue("createdAtEpochMs").jsonPrimitive.content.toLong(),
            ),
        )
        val expected = textOf(header.getValue("canonicalJson")).toByteArray(Charsets.UTF_8)
        assertEquals(header.getValue("byteLength").jsonPrimitive.int, expected.size)
        assertEquals(String(expected, Charsets.UTF_8), String(rebuilt, Charsets.UTF_8))
        assertTrue("头部必须逐字节相同（它是 AAD）", expected.contentEquals(rebuilt))
        // 字段顺序固定：version 在最前，createdAtEpochMs 在最后。
        assertTrue(String(rebuilt, Charsets.UTF_8).startsWith("{\"version\":1,\"kdf\":\"pbkdf2-sha256\",\"iterations\":600000,"))
    }

    // ------------------------------------------------------------------ 归档逐字节

    @Test
    fun kotlin_reproduces_the_windows_archive_byte_for_byte() {
        val header = headerRecord()
        val salt = base64(textOf(header.getValue("saltBase64")))
        val nonce = base64(textOf(header.getValue("nonceBase64")))
        val headerJson = ArchiveHeaderCodec.serialize(
            ArchiveHeader(
                version = ArchiveHeader.CURRENT_VERSION,
                kdf = ArchiveKdf.PBKDF2_SHA256_NAME,
                iterations = ArchiveKdf.MIN_ITERATIONS,
                saltBase64 = textOf(header.getValue("saltBase64")),
                nonceBase64 = textOf(header.getValue("nonceBase64")),
                createdAtEpochMs = header.getValue("createdAtEpochMs").jsonPrimitive.content.toLong(),
            ),
        )
        val payload = payloadBytes()
        assertEquals(
            vectors.getValue("envelope").jsonObject.getValue("byteLength").jsonPrimitive.int,
            payload.size,
        )
        assertEquals(
            textOf(vectors.getValue("envelope").jsonObject.getValue("sha256")),
            sha256(payload),
        )

        // 用与 Windows 完全相同的输入（固定 salt / nonce / 明文载荷原字节）复算归档。
        val prefix = ArchiveWriter.buildPrefix(headerJson)
        val key = ArchiveKdf.deriveKey(textOf(vectors.getValue("passphrase")), salt, ArchiveKdf.MIN_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(prefix)
        val ciphertextAndTag = cipher.doFinal(payload)
        val rebuilt = ByteArray(prefix.size + ciphertextAndTag.size)
        prefix.copyInto(rebuilt, 0)
        ciphertextAndTag.copyInto(rebuilt, prefix.size)

        val expectedHex = textOf(archiveRecord().getValue("hex"))
        assertEquals(archiveRecord().getValue("byteLength").jsonPrimitive.int, rebuilt.size)
        assertEquals("归档必须逐字节相同", expectedHex, toHex(rebuilt))
        assertEquals(textOf(archiveRecord().getValue("sha256")), sha256(rebuilt))
        assertTrue("向量里的 base64 与 hex 是同一份字节", base64(textOf(archiveRecord().getValue("base64"))).contentEquals(rebuilt))
    }

    @Test
    fun the_frozen_archive_decrypts_back_to_the_same_envelope() {
        val archiveBytes = base64(textOf(archiveRecord().getValue("base64")))
        val (envelope, reason) = ArchiveReader.tryRead(archiveBytes, textOf(vectors.getValue("passphrase")))
        assertNull("必须能读开，实际失败码 = " + reason, reason)
        val expected = SourceSyncCodec.decode(payloadBytes().toString(Charsets.UTF_8))
        assertEquals("结构化比较（两端对非 ASCII 转义策略不同，不能比字节）", expected, envelope)
        assertEquals("device-a", envelope!!.deviceId)
        assertEquals(3, envelope.sources.size)
    }

    @Test
    fun the_header_can_be_read_without_any_passphrase() {
        val archiveBytes = base64(textOf(archiveRecord().getValue("base64")))
        val header = ArchiveReader.tryReadHeader(archiveBytes)
        assertEquals(ArchiveHeader.CURRENT_VERSION, header!!.version)
        assertEquals(ArchiveKdf.PBKDF2_SHA256_NAME, header.kdf)
        assertEquals(ArchiveKdf.MIN_ITERATIONS, header.iterations)
        assertEquals(textOf(headerRecord().getValue("saltBase64")), header.saltBase64)
        assertEquals(textOf(headerRecord().getValue("nonceBase64")), header.nonceBase64)
        assertEquals(1_700_000_000_000L, header.createdAtEpochMs)
    }

    // ------------------------------------------------------------------ 错误向量：码必须同名

    @Test
    fun every_error_vector_reports_the_reason_windows_reports() {
        val errors = vectors.getValue("errors").jsonArray
        assertEquals(19, errors.size)
        val mismatches = ArrayList<String>()
        for (element in errors) {
            val record = element.jsonObject
            val name = textOf(record.getValue("name"))
            val expectedReason = textOf(record.getValue("reason"))
            val bytes = base64(textOf(record.getValue("archiveBase64")))
            val passphrase = record.getValue("passphrase").jsonPrimitive.content
            val (envelope, reason) = ArchiveReader.tryRead(bytes, passphrase)
            if (envelope != null) {
                mismatches.add(name + "：本端判成功，Windows 判 " + expectedReason)
                continue
            }
            if (reason != expectedReason) {
                mismatches.add(name + "：本端 " + reason + "，Windows " + expectedReason)
            }
        }
        assertTrue("错误向量码不一致：" + mismatches.joinToString(" | "), mismatches.isEmpty())
    }

    @Test
    fun every_error_vector_agrees_on_whether_the_header_parses() {
        val mismatches = ArrayList<String>()
        for (element in vectors.getValue("errors").jsonArray) {
            val record = element.jsonObject
            val name = textOf(record.getValue("name"))
            val expected = record.getValue("headerParses").jsonPrimitive.content.toBoolean()
            val bytes = base64(textOf(record.getValue("archiveBase64")))
            val parsed = ArchiveReader.tryReadHeader(bytes) != null
            if (parsed != expected) mismatches.add(name + "：本端 " + parsed + "，Windows " + expected)
        }
        assertTrue("headerParses 不一致：" + mismatches.joinToString(" | "), mismatches.isEmpty())
    }

    @Test
    fun the_error_vectors_cover_every_bucket_a_reader_can_report() {
        val reasons = vectors.getValue("errors").jsonArray
            .map { it.jsonObject.getValue("reason").jsonPrimitive.content }
            .toSet()
        assertTrue(reasons.contains("magic-mismatch"))
        assertTrue(reasons.contains("corrupt-header"))
        assertTrue(reasons.contains("unsupported-version:2"))
        assertTrue(reasons.contains("unknown-kdf:argon2id"))
        assertTrue(reasons.contains("invalid-iterations"))
        assertTrue(reasons.contains("corrupt-ciphertext"))
        assertTrue(reasons.contains("wrong-passphrase-or-tampered"))
        assertTrue(reasons.contains("passphrase-empty"))
        assertTrue(reasons.contains("passphrase-too-weak"))
        assertTrue(reasons.contains("malformed-payload"))
        assertTrue(reasons.contains("secrets-not-allowed"))
        // 每一条都能在码表里找到归宿（带参数的两个按前缀认）。
        assertTrue(
            reasons.all { reason ->
                ArchiveError.ALL.any { base -> reason == base || reason.startsWith(base + ":") }
            },
        )
    }

    // ------------------------------------------------------------------ 写侧判定

    @Test
    fun every_writer_case_reports_the_reason_windows_reports() {
        val cases = vectors.getValue("writerCases").jsonArray
        assertEquals(9, cases.size)
        val mismatches = ArrayList<String>()
        for (element in cases) {
            val record = element.jsonObject
            val name = textOf(record.getValue("name"))
            val expected = textOf(record.getValue("reason"))
            val envelopeJson = textOf(record.getValue("envelopeJson"))
            val passphrase = record.getValue("passphrase").jsonPrimitive.content
            val iterations = record.getValue("iterations").jsonPrimitive.int
            val observed = try {
                ArchiveWriter.write(
                    SourceSyncCodec.decode(envelopeJson),
                    passphrase,
                    iterations,
                    createdAtEpochMs = 1_700_000_000_000L,
                )
                "ok"
            } catch (error: ArchiveException) {
                error.reason
            }
            if (observed != expected) mismatches.add(name + "：本端 " + observed + "，Windows " + expected)
        }
        assertTrue("写侧判定不一致：" + mismatches.joinToString(" | "), mismatches.isEmpty())
    }

    @Test
    fun the_writer_case_order_proves_the_passphrase_then_secrets_then_iterations_precedence() {
        // 三个不合法条件同时成立时 Windows 报哪个码，就是判定序的直接证据：
        // 口令错 + 带凭据块 + 600000 轮 ⇒ passphrase-empty（口令最先）；
        // 口令对 + 带凭据标志 + 1000 轮 ⇒ secrets-not-allowed（凭据先于迭代数）。
        val byName = HashMap<String, JsonObject>()
        for (element in vectors.getValue("writerCases").jsonArray) {
            val record = element.jsonObject
            byName[textOf(record.getValue("name"))] = record
        }
        assertEquals(9, byName.size)
        assertEquals("passphrase-empty", textOf(byName.getValue("passphrase-before-secrets").getValue("reason")))
        assertEquals("secrets-not-allowed", textOf(byName.getValue("secrets-before-iterations").getValue("reason")))
        assertEquals(1_000, byName.getValue("secrets-before-iterations").getValue("iterations").jsonPrimitive.int)
        assertEquals(600_000, byName.getValue("passphrase-before-secrets").getValue("iterations").jsonPrimitive.int)
    }

    // ------------------------------------------------------------------ 口令策略

    @Test
    fun the_passphrase_policy_agrees_with_every_frozen_candidate() {
        val passphrases = vectors.getValue("passphrases").jsonObject
        val valid = passphrases.getValue("valid").jsonArray.map { it.jsonPrimitive.content }
        val invalid = passphrases.getValue("invalid").jsonArray.map { it.jsonObject }
        assertEquals(4, valid.size)
        assertEquals(9, invalid.size)
        for (candidate in valid) {
            assertNull("本应合法：" + candidate, ArchivePassphrase.tryValidate(candidate))
        }
        for (record in invalid) {
            val candidate = textOf(record.getValue("passphrase"))
            val expected = textOf(record.getValue("reason"))
            assertEquals("口令 " + candidate + " 的判定", expected, ArchivePassphrase.tryValidate(candidate))
        }
    }

    @Test
    fun the_passphrase_policy_is_identical_to_the_windows_twenty_four_word_deny_list() {
        // 逐条过一遍弱口令表（照抄 Windows 的 24 条，顺序无关但内容必须齐）。
        val weak = listOf(
            "password", "password1", "password123", "password1234", "passw0rd",
            "123456", "1234567", "12345678", "123456789", "1234567890",
            "12345678901", "123456789012", "qwertyuiop", "qwerty123456", "qwertyuiop123",
            "1q2w3e4r5t6y", "letmein12345", "iloveyou1234", "administrator", "trustno1trustno1",
            "aaaaaaaaaaaa", "abcabcabcabc", "changeme1234", "welcome12345",
        )
        assertEquals(24, weak.size)
        for (candidate in weak) {
            assertEquals(candidate, ArchiveError.PASSPHRASE_TOO_WEAK, ArchivePassphrase.tryValidate(candidate))
        }
        // 单字符重复与全数字两类形状，即使不在表里也拒。
        assertEquals(ArchiveError.PASSPHRASE_TOO_WEAK, ArchivePassphrase.tryValidate("zzzzzzzzzzzzzzzz"))
        assertEquals(ArchiveError.PASSPHRASE_TOO_WEAK, ArchivePassphrase.tryValidate("98765432109876"))
        // 12 字边界：11 拒 12 收（未 trim 的原始长度）。
        assertEquals(ArchiveError.PASSPHRASE_TOO_WEAK, ArchivePassphrase.tryValidate("我的 NAS 在阁楼里"))
        assertNull(ArchivePassphrase.tryValidate("我的 NAS 放进了阁楼"))
        assertNull(ArchivePassphrase.tryValidate("correct horse battery stapl"))
        assertEquals(ArchiveError.PASSPHRASE_EMPTY, ArchivePassphrase.tryValidate("   "))
        assertEquals(ArchiveError.PASSPHRASE_EMPTY, ArchivePassphrase.tryValidate(null))
    }
}
