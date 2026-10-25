package com.mediahub.core.common.archive

import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceTombstone
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncSourceTypeValue
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档的纯 Kotlin 回环与边界（不依赖 Windows 向量）。
 *
 * 与 ArchiveVectorsTest 分工：那边钉「两端逐字节一致」，这边钉「本端自己写、自己读、自己篡改」
 * 的行为面 —— 同一份实现被改坏时，两组用例会从不同方向报警。
 *
 * 本文件还钉了一条**跨端契约上的真缺陷**：Windows 判「携带凭据」的判据是 ValueKind != Null，
 * 所以 secrets: null 不算凭据；Kotlin 里 JsonNull 是非 null 对象，只写 != null 会把显式 null
 * 误判成凭据，导致同一信封 Windows 允许写、Android 拒绝。见 a_json_null_secrets_block_is_not_credentials_on_either_side。
 */
class ArchiveRoundTripTest {

    private val passphrase = "correct horse battery staple"

    private fun endpoint(url: String, primary: Boolean = true, order: Int = 0) = SourceEndpoint(
        url = url,
        isPrimary = primary,
        enabled = true,
        sortOrder = order,
    )

    private fun record(
        id: String,
        name: String,
        accountScope: String? = null,
        vararg endpoints: SourceEndpoint,
    ) = SourceRecord(
        syncSourceId = id,
        sourceType = SyncSourceTypeValue.parse("emby"),
        displayName = name,
        accountScope = accountScope,
        endpoints = endpoints.toList(),
        recordRevision = 7L,
        fieldVersion = 2,
        secrets = null,
    )

    private fun envelope(
        sources: List<SourceRecord> = listOf(record("local-1", "客厅 Emby", "alice", endpoint("http://nas-a:8096/emby"))),
        deletions: List<SourceTombstone> = emptyList(),
        canSendSecrets: Boolean = false,
    ) = SourceSyncEnvelope(
        deviceId = "device-android",
        snapshotId = "0123456789abcdef0123456789abcdef",
        createdAtEpochMs = 1_700_000_000_000L,
        capabilities = SyncCapabilities(canSendSecrets = canSendSecrets, sourceTypes = listOf("emby")),
        sources = sources,
        deletions = deletions,
    )

    private fun written(
        envelope: SourceSyncEnvelope = envelope(),
        passphrase: String? = this.passphrase,
        iterations: Int = ArchiveKdf.DEFAULT_ITERATIONS,
    ): ByteArray = ArchiveWriter.write(envelope, passphrase, iterations, createdAtEpochMs = 1_700_000_000_000L)

    private fun reasonOf(block: () -> Unit): String? = try {
        block()
        null
    } catch (error: ArchiveException) {
        error.reason
    }

    private fun headerBytes(archive: ByteArray): ByteArray {
        val magic = ArchiveHeader.CURRENT_MAGIC.toByteArray(Charsets.US_ASCII)
        val headerLength = java.nio.ByteBuffer.wrap(archive, magic.size, 4).int
        return archive.copyOfRange(magic.size + 4, magic.size + 4 + headerLength)
    }

    private fun trailerOffset(archive: ByteArray): Int {
        val magic = ArchiveHeader.CURRENT_MAGIC.toByteArray(Charsets.US_ASCII)
        val headerLength = java.nio.ByteBuffer.wrap(archive, magic.size, 4).int
        return magic.size + 4 + headerLength
    }

    /** 在头部 JSON 里改一个**等长**子串：AAD 变了，但结构仍然合法。 */
    private fun patchHeader(archive: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length) { "替换必须等长，否则后面所有偏移都会漂移" }
        val magic = ArchiveHeader.CURRENT_MAGIC.toByteArray(Charsets.US_ASCII)
        val json = headerBytes(archive).toString(Charsets.UTF_8)
        val index = json.indexOf(from)
        require(index >= 0) { "头部里找不到 " + from }
        require(json.indexOf(from, index + from.length) < 0) { "头部里 " + from + " 不唯一" }
        val copy = archive.copyOf()
        to.toByteArray(Charsets.US_ASCII).copyInto(copy, magic.size + 4 + index)
        return copy
    }

    /** 手搓一个「载荷是指定 JSON」的归档：写侧会先拒绝这些输入，只能这样测读侧的载荷类判定。 */
    private fun craft(payloadJson: String, passphrase: String = this.passphrase): ByteArray {
        val salt = ByteArray(ArchiveKdf.SALT_SIZE) { (it and 0xFF).toByte() }
        val nonce = ByteArray(ArchiveHeader.NONCE_SIZE) { (0x20 + it).toByte() }
        val header = ArchiveHeader(
            version = ArchiveHeader.CURRENT_VERSION,
            kdf = ArchiveKdf.PBKDF2_SHA256_NAME,
            iterations = ArchiveKdf.DEFAULT_ITERATIONS,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            nonceBase64 = Base64.getEncoder().encodeToString(nonce),
            createdAtEpochMs = 1_700_000_000_000L,
        )
        val prefix = ArchiveWriter.buildPrefix(ArchiveHeaderCodec.serialize(header))
        val key = ArchiveKdf.deriveKey(passphrase, salt, header.iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(prefix)
            return prefix + cipher.doFinal(payloadJson.toByteArray(Charsets.UTF_8))
        } finally {
            key.fill(0)
        }
    }

    // ------------------------------------------------------------------ 回环

    @Test
    fun a_written_archive_reads_back_with_every_field_intact() {
        val original = envelope(
            sources = listOf(
                record(
                    "local-1",
                    "客厅 Emby",
                    "alice",
                    endpoint("http://nas-a:8096/emby"),
                    endpoint("http://nas-a:8097/emby", primary = false, order = 1),
                ),
                record("local-2", "书房 Jellyfin", null, endpoint("https://nas-b:8920/jelly")),
            ),
            deletions = listOf(SourceTombstone("local-9", 1_760_000_000_000L, 5L)),
        )
        val archive = written(original)
        val (decoded, reason) = ArchiveReader.tryRead(archive, passphrase)
        assertNull("回环必须读开，实际失败码 = " + reason, reason)
        assertEquals(original, decoded)
        assertEquals(1_700_000_000_000L, ArchiveReader.tryReadHeader(archive)!!.createdAtEpochMs)
    }

    @Test
    fun an_empty_envelope_also_round_trips() {
        val original = envelope(sources = emptyList(), deletions = emptyList())
        assertEquals(original, ArchiveReader.tryRead(written(original), passphrase).first)
    }

    @Test
    fun writing_twice_never_repeats_salt_nonce_or_ciphertext() {
        val first = written()
        val second = written()
        val firstHeader = ArchiveReader.tryReadHeader(first)!!
        val secondHeader = ArchiveReader.tryReadHeader(second)!!
        assertNotEquals("salt 必须每次新生成", firstHeader.saltBase64, secondHeader.saltBase64)
        assertNotEquals("nonce 必须每次新生成", firstHeader.nonceBase64, secondHeader.nonceBase64)
        assertFalse("密文必须不同", first.contentEquals(second))
        assertEquals(ArchiveReader.tryRead(first, passphrase).first, ArchiveReader.tryRead(second, passphrase).first)
    }

    @Test
    fun the_reader_is_pure_and_can_be_called_repeatedly() {
        val archive = written()
        val snapshot = archive.copyOf()
        val once = ArchiveReader.tryRead(archive, passphrase).first
        val twice = ArchiveReader.tryRead(archive, passphrase).first
        assertEquals(once, twice)
        assertTrue("reader 不得改动入参字节", snapshot.contentEquals(archive))
        assertNull("成功路径不得留下 lastReason", ArchiveReader.lastReason)
    }

    // ------------------------------------------------------------------ 篡改：AAD 与 tag

    @Test
    fun a_tampered_tag_byte_is_wrong_passphrase_or_tampered() {
        val archive = written()
        archive[archive.size - 1] = (archive[archive.size - 1].toInt() xor 0x01).toByte()
        assertEquals(ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED, ArchiveReader.tryRead(archive, passphrase).second)
    }

    @Test
    fun a_tampered_ciphertext_byte_is_wrong_passphrase_or_tampered() {
        val archive = written()
        val trailer = trailerOffset(archive)
        archive[trailer] = (archive[trailer].toInt() xor 0x40).toByte()
        assertEquals(ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED, ArchiveReader.tryRead(archive, passphrase).second)
    }

    @Test
    fun a_tampered_header_timestamp_still_parses_but_never_decrypts() {
        val archive = written()
        val patched = patchHeader(archive, "\"createdAtEpochMs\":1700000000000", "\"createdAtEpochMs\":1700000000001")
        assertEquals(
            "改了时间戳的头部仍然是合法头部（头部分是明文）",
            1_700_000_000_001L,
            ArchiveReader.tryReadHeader(patched)!!.createdAtEpochMs,
        )
        assertEquals(
            "头部是 AAD，改一个字节就过不了 tag",
            ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED,
            ArchiveReader.tryRead(patched, passphrase).second,
        )
    }

    @Test
    fun a_tampered_version_reports_unsupported_version_before_any_passphrase_work() {
        val archive = written()
        val patched = patchHeader(archive, "\"version\":1", "\"version\":2")
        assertEquals("unsupported-version:2", ArchiveReader.tryRead(patched, passphrase).second)
        assertEquals(
            "结构错误优先于口令错误：连空口令也先报版本",
            "unsupported-version:2",
            ArchiveReader.tryRead(patched, null).second,
        )
        assertNull("版本不认识时头部也不该被当成可用头部", ArchiveReader.tryReadHeader(patched))
    }

    @Test
    fun a_tampered_kdf_name_reports_unknown_kdf_with_the_name() {
        val archive = written()
        val patched = patchHeader(archive, "pbkdf2-sha256", "pbkdf2-sha51x")
        assertEquals("unknown-kdf:pbkdf2-sha51x", ArchiveReader.tryRead(patched, passphrase).second)
    }

    @Test
    fun a_tampered_iteration_count_is_refused_at_the_structural_layer() {
        val archive = written()
        // 600001 仍然 ≥ 下限：结构层放行，但 AAD 变了 ⇒ 只会在解密层失败。
        val raised = patchHeader(archive, "\"iterations\":600000", "\"iterations\":600001")
        assertEquals(
            ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED,
            ArchiveReader.tryRead(raised, passphrase).second,
        )
        // 100000 < 600000：结构层直接拒，连密钥都不派生（降迭代数攻击在读侧被挡）。
        val lowered = patchHeader(archive, "\"iterations\":600000", "\"iterations\":100000")
        assertEquals(ArchiveError.INVALID_ITERATIONS, ArchiveReader.tryRead(lowered, passphrase).second)
        assertEquals(
            "结构层的迭代数检查不需要口令",
            ArchiveError.INVALID_ITERATIONS,
            ArchiveReader.tryRead(lowered, null).second,
        )
    }

    @Test
    fun a_truncated_archive_never_reports_a_decryption_error() {
        val archive = written()
        val trailer = trailerOffset(archive)
        // 扣掉头部之后只剩 8 字节（连 tag 的 16 字节都不够）⇒ 结构层判「没有密文」。
        val noCiphertext = archive.copyOfRange(0, trailer + 8)
        assertEquals(ArchiveError.CORRUPT_CIPHERTEXT, ArchiveReader.tryRead(noCiphertext, passphrase).second)
        // 正好扣掉整个 tag：密文长度合法，但最后 16 字节被当成 tag ⇒ 解密层失败。
        val noTag = archive.copyOfRange(0, archive.size - ArchiveHeader.TAG_SIZE)
        assertEquals(ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED, ArchiveReader.tryRead(noTag, passphrase).second)
        // 头部都没读完。
        val insideHeader = archive.copyOfRange(0, ArchiveHeader.CURRENT_MAGIC.length + 6)
        assertEquals(ArchiveError.CORRUPT_HEADER, ArchiveReader.tryRead(insideHeader, passphrase).second)
        // 短于 magic。
        assertEquals(ArchiveError.MAGIC_MISMATCH, ArchiveReader.tryRead(archive.copyOfRange(0, 4), passphrase).second)
        assertEquals(ArchiveError.MAGIC_MISMATCH, ArchiveReader.tryRead(ByteArray(0), passphrase).second)
    }

    // ------------------------------------------------------------------ 凭据：两侧都拒

    @Test
    fun writing_refuses_an_envelope_that_declares_can_send_secrets() {
        assertEquals(ArchiveError.SECRETS_NOT_ALLOWED, reasonOf { written(envelope(canSendSecrets = true)) })
    }

    @Test
    fun writing_refuses_an_envelope_that_carries_a_secrets_block_even_when_the_flag_is_off() {
        val smuggled = envelope().copy(
            sources = listOf(
                record("local-1", "客厅 Emby").copy(
                    secrets = buildJsonObject {
                        put("username", "alice")
                        put("password", "not-a-real-secret")
                    },
                ),
            ),
        )
        assertFalse(smuggled.capabilities.canSendSecrets)
        assertEquals(
            "标志位为 off 也要查真实块（拒绝而不是静默剥掉）",
            ArchiveError.SECRETS_NOT_ALLOWED,
            reasonOf { written(smuggled) },
        )
    }

    @Test
    fun a_json_null_secrets_block_is_not_credentials_on_either_side() {
        // Windows 判的是 ValueKind != Null；显式 null 不算携带。两端必须同结论。
        val withNull = envelope().copy(sources = listOf(record("local-1", "客厅 Emby").copy(secrets = JsonNull)))
        assertNull("显式 null 不是凭据，写侧不得拒绝", reasonOf { written(withNull) })
        val archive = written(withNull)
        assertNull("读回来也正常", ArchiveReader.tryRead(archive, passphrase).second)
        assertNull(ArchiveReader.tryRead(archive, passphrase).first!!.sources[0].secrets)
    }

    @Test
    fun reading_refuses_a_payload_that_declares_can_send_secrets() {
        val payload = SourceSyncCodec.encode(envelope(canSendSecrets = true))
        assertEquals(ArchiveError.SECRETS_NOT_ALLOWED, ArchiveReader.tryRead(craft(payload), passphrase).second)
    }

    // ------------------------------------------------------------------ 口令

    @Test
    fun the_passphrase_boundary_is_eleven_refused_and_twelve_accepted() {
        assertEquals(ArchiveError.PASSPHRASE_TOO_WEAK, reasonOf { written(passphrase = "我的 NAS 在阁楼里") })
        assertEquals(ArchiveError.PASSPHRASE_TOO_WEAK, reasonOf { written(passphrase = "short") })
        assertEquals(ArchiveError.PASSPHRASE_EMPTY, reasonOf { written(passphrase = "") })
        assertEquals(ArchiveError.PASSPHRASE_EMPTY, reasonOf { written(passphrase = "   ") })
        assertEquals(ArchiveError.PASSPHRASE_EMPTY, reasonOf { written(passphrase = null) })
        val minimal = written(passphrase = "我的 NAS 放进了阁楼")
        assertEquals(envelope(), ArchiveReader.tryRead(minimal, "我的 NAS 放进了阁楼").first)
    }

    @Test
    fun a_chinese_passphrase_round_trips_and_a_wrong_one_does_not() {
        val chinese = "我的 NAS 放进了阁楼"
        val archive = written(passphrase = chinese)
        assertEquals(envelope(), ArchiveReader.tryRead(archive, chinese).first)
        assertEquals(
            ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED,
            ArchiveReader.tryRead(archive, "我的 NAS 放进了隔壁").second,
        )
        assertEquals(ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED, ArchiveReader.tryRead(archive, passphrase).second)
    }

    @Test
    fun a_low_iteration_count_is_refused_by_the_writer_and_the_boundary_is_allowed() {
        assertEquals(ArchiveError.INVALID_ITERATIONS, reasonOf { written(iterations = 1_000) })
        assertEquals(
            ArchiveError.INVALID_ITERATIONS,
            reasonOf { written(iterations = ArchiveKdf.MIN_ITERATIONS - 1) },
        )
        val atMinimum = written(iterations = ArchiveKdf.MIN_ITERATIONS)
        assertEquals(ArchiveKdf.MIN_ITERATIONS, ArchiveReader.tryReadHeader(atMinimum)!!.iterations)
        assertEquals(envelope(), ArchiveReader.tryRead(atMinimum, passphrase).first)
    }

    // ------------------------------------------------------------------ 不可区分与不泄露

    @Test
    fun a_wrong_passphrase_and_a_tampered_file_report_the_exact_same_code() {
        val archive = written()
        val wrong = ArchiveReader.tryRead(archive, "a different long passphrase").second
        val tampered = archive.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 0x08).toByte() }
        val tamperedReason = ArchiveReader.tryRead(tampered, passphrase).second
        assertEquals(ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED, wrong)
        assertEquals("两个码必须完全相同（否则成了口令 oracle）", wrong, tamperedReason)
    }

    @Test
    fun the_describe_texts_never_quote_the_payload_or_the_passphrase() {
        val texts = listOf(
            ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED,
            ArchiveError.SECRETS_NOT_ALLOWED,
            ArchiveError.MALFORMED_PAYLOAD,
            ArchiveError.MAGIC_MISMATCH,
        ).map { ArchiveReader.describe(it) }
        for (text in texts) {
            assertFalse(text, text.contains(passphrase))
            assertFalse(text, text.contains("客厅 Emby"))
            assertFalse(text, text.contains("local-1"))
        }
        assertTrue(ArchiveReader.describe(ArchiveError.MALFORMED_PAYLOAD).startsWith("The archive decrypted but"))
        assertTrue(ArchiveReader.describe(ArchiveError.MAGIC_MISMATCH).startsWith("The archive could not be read:"))
    }

    @Test
    fun the_archive_bytes_never_contain_the_plaintext() {
        val archive = written()
        val latin1 = archive.toString(Charsets.ISO_8859_1)
        assertFalse("明文 displayName 不得出现在归档字节里", latin1.contains("客厅 Emby"))
        assertFalse("明文 deviceId 不得出现在归档字节里", latin1.contains("device-android"))
        assertFalse("明文 syncSourceId 不得出现在归档字节里", latin1.contains("local-1"))
        assertTrue("magic 是明文（这是设计：不解析就能拒外文件）", latin1.startsWith(ArchiveHeader.CURRENT_MAGIC))
    }

    // ------------------------------------------------------------------ 载荷类

    @Test
    fun a_payload_that_is_not_an_envelope_is_malformed_payload() {
        assertEquals(ArchiveError.MALFORMED_PAYLOAD, ArchiveReader.tryRead(craft("{\"hello\":1}"), passphrase).second)
        assertEquals(ArchiveError.MALFORMED_PAYLOAD, ArchiveReader.tryRead(craft("not json at all"), passphrase).second)
        assertNotNull("载荷坏不影响头部可读（头部是明文）", ArchiveReader.tryReadHeader(craft("{}")))
    }

    @Test
    fun a_payload_with_an_unknown_schema_version_stays_malformed_payload() {
        val bumped = SourceSyncCodec.encode(envelope()).replace("\"schemaVersion\":1", "\"schemaVersion\":9")
        assertEquals(
            "归档内信封版本不对时仍归到 malformed-payload（十二码表不扩容）",
            ArchiveError.MALFORMED_PAYLOAD,
            ArchiveReader.tryRead(craft(bumped), passphrase).second,
        )
    }
}
