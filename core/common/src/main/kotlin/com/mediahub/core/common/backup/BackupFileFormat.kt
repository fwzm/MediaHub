package com.mediahub.core.common.backup

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 本地备份文件格式（Phase 1I-A，`.mhbackup`）：
 * 外层 JSON envelope 含解密所需最小头部（magic/版本/KDF 参数/nonce/密文）；
 * 内层 JSON 为白名单快照——服务器地址、用户名、观看记录、偏好。
 * 服务器地址不做明文暴露在外层；内层不携带凭据。
 */
object BackupFileFormat {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** 外层 envelope（序列化到磁盘）。 */
    @Serializable
    data class Envelope(
        @SerialName("magic") val magic: String,
        @SerialName("formatVersion") val formatVersion: Int,
        @SerialName("kdfAlgorithm") val kdfAlgorithm: String,
        @SerialName("kdfSaltB64") val kdfSaltB64: String,
        @SerialName("kdfIterations") val kdfIterations: Int,
        @SerialName("kdfKeyLengthBits") val kdfKeyLengthBits: Int,
        @SerialName("nonceB64") val nonceB64: String,
        @SerialName("ciphertextB64") val ciphertextB64: String,
    )

    /** 内层 manifest（解密后 JSON 首部）。 */
    @Serializable
    data class Manifest(
        @SerialName("formatVersion") val formatVersion: Int,
        @SerialName("minimumReaderVersion") val minimumReaderVersion: Int,
        @SerialName("appVersion") val appVersion: String,
        @SerialName("createdAtEpochMs") val createdAtEpochMs: Long,
        @SerialName("includedSections") val includedSections: List<String>,
        @SerialName("recordCounts") val recordCounts: Map<String, Int>,
    )

    fun encodeEnvelope(envelope: Envelope): String = json.encodeToString(Envelope.serializer(), envelope)

    fun decodeEnvelope(data: String): Envelope = json.decodeFromString(Envelope.serializer(), data)

    fun encodeManifest(manifest: Manifest): String = json.encodeToString(Manifest.serializer(), manifest)

    fun decodeManifest(data: String): Manifest = json.decodeFromString(Manifest.serializer(), data)

    fun encodeB64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decodeB64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
