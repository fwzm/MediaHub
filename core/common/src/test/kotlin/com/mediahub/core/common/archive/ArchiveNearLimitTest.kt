package com.mediahub.core.common.archive

import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncSourceTypeValue
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8 MiB 上限附近的真数据穿越 AES-256-GCM（W6-68）。
 *
 * 既有用例只钉了两端「拒绝过大载荷」：Kotlin 用 9 MB 断言 payload-too-large、C# 用
 * MaxPayloadBytes + 1 断言读端在解密前就拒。它们都没有回答真正会撞上的问题：
 * 载荷恰好等于 8 MiB（8388608 B）时，加密、写盘、读回、GCM 校验整条路还通不通。
 *
 * 资源 archive/windows-archive-vectors.json 的 nearLimit 段由 Windows 权威实现生成
 * （D:/mh-verify/archive-vectors，临时工程不入库）：配方是一份恰好 8 MiB 的信封，
 * displayName 由 ASCII 单字符重复到精确长度；段里只存长度与 sha256，不存 8 MB 原字节。
 *
 * 本文件同时钉住：Kotlin 用同一配方 encode 出的载荷与 Windows 逐字节一致；用固定 salt/nonce
 * 手工加密出的归档与 Windows 的归档 sha256 一致；写端接受恰好上限、拒绝 MAX+1。
 */
class ArchiveNearLimitTest {

    private val vectors: JsonObject by lazy {
        Json.parseToJsonElement(resource("windows-archive-vectors.json")).jsonObject
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/archive/" + name)) { "缺少测试资源 /archive/" + name }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun nearLimit(): JsonObject = vectors.getValue("nearLimit").jsonObject

    private fun recipe(): JsonObject = nearLimit().getValue("recipe").jsonObject

    private fun textOf(value: kotlinx.serialization.json.JsonElement): String = value.jsonPrimitive.content

    private fun passphrase(): String = textOf(recipe().getValue("passphrase"))

    /** 按向量里的配方重建同一份信封：只有 displayName 长度是变量，用来精确顶到上限或 MAX + 1。 */
    private fun envelopeWithDisplayName(displayNameLength: Int): SourceSyncEnvelope {
        val r = recipe()
        val capabilities = r.getValue("capabilities").jsonObject
        return SourceSyncEnvelope(
            schemaVersion = 1,
            minimumReaderVersion = 1,
            deviceId = textOf(r.getValue("deviceId")),
            snapshotId = textOf(r.getValue("snapshotId")),
            createdAtEpochMs = r.getValue("createdAtEpochMs").jsonPrimitive.long,
            capabilities = SyncCapabilities(
                canSendSecrets = capabilities.getValue("canSendSecrets").jsonPrimitive.content.toBoolean(),
                sourceTypes = listOf("emby"),
            ),
            sources = listOf(
                SourceRecord(
                    syncSourceId = textOf(r.getValue("syncSourceId")),
                    sourceType = SyncSourceTypeValue.parse(textOf(r.getValue("sourceType"))),
                    displayName = textOf(r.getValue("displayNameUnit")).repeat(displayNameLength),
                    accountScope = null,
                    endpoints = emptyList(),
                    recordRevision = r.getValue("recordRevision").jsonPrimitive.long,
                    fieldVersion = r.getValue("fieldVersion").jsonPrimitive.int,
                    secrets = null,
                ),
            ),
            deletions = emptyList(),
        )
    }

    private fun envelope(): SourceSyncEnvelope =
        envelopeWithDisplayName(recipe().getValue("displayNameLength").jsonPrimitive.int)

    private fun header(): ArchiveHeader = ArchiveHeader(
        version = ArchiveHeader.CURRENT_VERSION,
        kdf = ArchiveKdf.PBKDF2_SHA256_NAME,
        iterations = recipe().getValue("iterations").jsonPrimitive.int,
        saltBase64 = textOf(recipe().getValue("saltBase64")),
        nonceBase64 = textOf(recipe().getValue("nonceBase64")),
        createdAtEpochMs = recipe().getValue("createdAtEpochMs").jsonPrimitive.long,
    )

    /** 用向量里的固定 salt/nonce 手工加密：这样得到的归档才能和 Windows 的字节比较。 */
    private fun craft(payloadJson: String): ByteArray {
        val header = header()
        val salt = Base64.getDecoder().decode(header.saltBase64)
        val nonce = Base64.getDecoder().decode(header.nonceBase64)
        val prefix = ArchiveWriter.buildPrefix(ArchiveHeaderCodec.serialize(header))
        val key = ArchiveKdf.deriveKey(passphrase(), salt, header.iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(prefix)
            return prefix + cipher.doFinal(payloadJson.toByteArray(Charsets.UTF_8))
        } finally {
            key.fill(0)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            builder.append(Character.forDigit((byte.toInt() shr 4) and 0x0F, 16))
            builder.append(Character.forDigit(byte.toInt() and 0x0F, 16))
        }
        return builder.toString()
    }

    private fun reasonOf(block: () -> Unit): String? = try {
        block()
        null
    } catch (error: ArchiveException) {
        error.reason
    }

    // ------------------------------------------------------------ 配方与载荷

    @Test
    fun the_recipe_reproduces_the_windows_payload_byte_for_byte() {
        val record = nearLimit()
        val payload = SourceSyncCodec.encode(envelope()).toByteArray(Charsets.UTF_8)

        assertEquals(ArchiveHeader.MAX_PAYLOAD_BYTES, payload.size)
        assertEquals(record.getValue("payloadByteLength").jsonPrimitive.int, payload.size)
        assertEquals(textOf(record.getValue("payloadSha256")), sha256(payload))
    }

    @Test
    fun the_header_from_the_recipe_is_the_canonical_aad_windows_used() {
        val headerJson = ArchiveHeaderCodec.serialize(header())
        val expected = Base64.getDecoder().decode(textOf(nearLimit().getValue("headerBase64")))

        assertTrue("头部 JSON 必须逐字节等于 Windows 的 AAD", headerJson.contentEquals(expected));
        assertEquals(
            nearLimit().getValue("prefixByteLength").jsonPrimitive.int,
            ArchiveWriter.buildPrefix(headerJson).size,
        );
        assertEquals(ArchiveHeader.CURRENT_MAGIC.toByteArray(Charsets.US_ASCII).size + 4 + headerJson.size, ArchiveWriter.buildPrefix(headerJson).size);
    }

    // ------------------------------------------------------------ 8 MiB 穿越 GCM

    @Test
    fun a_payload_of_exactly_the_ceiling_encrypts_to_the_windows_archive_hash_and_reads_back() {
        val record = nearLimit()
        val envelope = envelope()
        val payloadJson = SourceSyncCodec.encode(envelope)
        val archive = craft(payloadJson)

        assertEquals(record.getValue("archiveByteLength").jsonPrimitive.int, archive.size)
        assertEquals(textOf(record.getValue("archiveSha256")), sha256(archive))

        val (decoded, reason) = ArchiveReader.tryRead(archive, passphrase())
        assertNull("8 MiB 载荷必须读得开，实际失败码 = " + reason, reason)
        assertEquals(envelope, decoded)
    }

    @Test
    fun the_writer_accepts_exactly_the_ceiling_and_refuses_one_byte_more() {
        val record = nearLimit()
        val envelope = envelope()

        assertEquals(ArchiveHeader.MAX_PAYLOAD_BYTES, SourceSyncCodec.encode(envelope).toByteArray(Charsets.UTF_8).size)

        // 真实写端自己生成随机 salt/nonce，所以只能钉「写→读回同一份信封」，不能钉长度。
        val written = ArchiveWriter.write(
            envelope,
            passphrase(),
            ArchiveKdf.DEFAULT_ITERATIONS,
            createdAtEpochMs = recipe().getValue("createdAtEpochMs").jsonPrimitive.long,
        )
        val (decoded, reason) = ArchiveReader.tryRead(written, passphrase())
        assertNull("恰好 8 MiB 的归档必须读得开，实际失败码 = " + reason, reason)
        assertEquals(envelope, decoded)

        val tooLarge = envelopeWithDisplayName(record.getValue("tooLarge").jsonObject.getValue("displayNameLength").jsonPrimitive.int)
        assertEquals(
            record.getValue("tooLarge").jsonObject.getValue("payloadByteLength").jsonPrimitive.int,
            SourceSyncCodec.encode(tooLarge).toByteArray(Charsets.UTF_8).size,
        );
        assertEquals(ArchiveHeader.MAX_PAYLOAD_BYTES + 1, SourceSyncCodec.encode(tooLarge).toByteArray(Charsets.UTF_8).size)
        assertEquals(
            textOf(record.getValue("tooLarge").jsonObject.getValue("writeReason")),
            reasonOf { ArchiveWriter.write(tooLarge, passphrase()) },
        );
    }

    @Test
    fun the_reader_refuses_an_oversized_ciphertext_before_decryption() {
        val record = nearLimit()
        val cipherLength = record.getValue("oversizedCiphertext").jsonObject.getValue("cipherLength").jsonPrimitive.int
        assertEquals(ArchiveHeader.MAX_PAYLOAD_BYTES + 1, cipherLength);

        val oversized = ArchiveWriter.buildPrefix(ArchiveHeaderCodec.serialize(header())) +
            ByteArray(cipherLength) + ByteArray(ArchiveHeader.TAG_SIZE)
        val (envelope, reason) = ArchiveReader.tryRead(oversized, passphrase())

        assertNull("读端必须在解密前就拒，不能先尝试解密", envelope)
        assertEquals(textOf(record.getValue("oversizedCiphertext").jsonObject.getValue("readReason")), reason)
        assertEquals(ArchiveError.PAYLOAD_TOO_LARGE, reason)
    }
}
