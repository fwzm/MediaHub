package com.mediahub.feature.settings.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.mediahub.model.*
import com.mediahub.core.common.backup.BackupDtos
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/** Full local before/after images. Never used by export or written to the plaintext journal. */
data class RestoreImages(
    val before: BackupSnapshot,
    val after: BackupSnapshot,
    val beforePreferences: UserPreferences,
    val afterPreferences: UserPreferences,
    val record: BackupDtos.RestorePlanRecord? = null,
)

/** Device-private authenticated storage; the journal contains only an opaque reference. */
interface RestoreSnapshotStorage {
    fun save(id: String, images: RestoreImages)
    fun read(id: String): RestoreImages
    fun delete(id: String)
}

class RestoreSnapshotStore private constructor(
    private val directory: File,
    private val key: () -> SecretKey,
) : RestoreSnapshotStorage {
    @Inject constructor(@ApplicationContext context: Context) : this(
        File(context.noBackupFilesDir, "restore-snapshots"), ::deviceKey,
    )

    /** Tests reopen the production file implementation with a test key, without pretending to test Keystore. */
    internal constructor(directory: File, key: SecretKey) : this(directory, { key })

    override fun save(id: String, images: RestoreImages) {
        check(directory.isDirectory || directory.mkdirs()) { "保护快照目录不可用" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val plain = RestoreImageCodec.encode(images)
        val encrypted = try {
            require(plain.size <= MAX_BYTES - 30) { "保护快照过大" }
            cipher.doFinal(plain)
        } finally { plain.fill(0) }
        require(encrypted.size.toLong() + cipher.iv.size + 2 <= MAX_BYTES) { "保护快照过大" }
        val file = file(id)
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1, cipher.iv.size.toByte()))
            stream.write(cipher.iv)
            stream.write(encrypted)
            stream.fd.sync()
            file.finishWrite(stream)
            // AtomicFile.finishWrite returns void and may only log a failed rename.
            // Reopen the published file and authenticate every field before allowing journal.begin.
            check(read(id) == images) { "保护快照未成功持久化" }
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    override fun read(id: String): RestoreImages {
        val bytes = file(id).openRead().use { input ->
            require(input.channel.size() <= MAX_BYTES) { "保护快照过大" }
            input.readBytes()
        }
        require(bytes.size >= 30 && bytes[0].toInt() == 1 && bytes[1].toInt() == 12) { "保护快照格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(2, 14)))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(bytes, 14, bytes.size - 14)
        return try { RestoreImageCodec.decode(plain) } finally { plain.fill(0) }
    }

    override fun delete(id: String) { file(id).delete() }
    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("restore-[a-f0-9-]+"))) { "保护快照引用无效" }
        return AtomicFile(File(directory, "$id.bin"))
    }

    private companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        fun deviceKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("mediahub_restore_snapshot_v1", null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("mediahub_restore_snapshot_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
    }
}

/** Explicit versioned binary codec, with no Java object deserialization and no auth/session fields. */
internal object RestoreImageCodec {
    fun encode(images: RestoreImages): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.snapshot(images.before); out.snapshot(images.after)
            out.preferences(images.beforePreferences); out.preferences(images.afterPreferences)
            out.text(images.record?.let(BackupDtos::encodePlanRecord))
        }
        bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): RestoreImages = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 1) { "保护快照版本无效" }
        RestoreImages(input.snapshot(), input.snapshot(), input.preferences(), input.preferences(),
            input.text()?.let(BackupDtos::decodePlanRecord)).also {
            require(input.available() == 0) { "保护快照尾部无效" }
        }
    }
    private fun DataOutputStream.text(value: String?) {
        if (value == null) writeInt(-1) else {
            val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes)
        }
    }
    private fun DataInputStream.text(): String? {
        val count = readInt(); if (count == -1) return null
        require(count in 0..available()) { "保护快照字段长度无效" }
        return ByteArray(count).also { readFully(it) }.toString(Charsets.UTF_8)
    }
    private fun DataInputStream.required(): String = requireNotNull(text())
    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..100_000) }
    private fun DataOutputStream.snapshot(snapshot: BackupSnapshot) {
        writeInt(snapshot.servers.size)
        snapshot.servers.forEach { s ->
            text(s.id); text(s.name); text(s.type.name); text(s.username); text(s.note); text(s.icon)
            writeBoolean(s.isDefault); writeInt(s.sortOrder); writeLong(s.createdAtEpochMs)
            text(s.lastConnectedAtEpochMs?.toString()); text(s.lastError); writeInt(s.endpoints.size)
            s.endpoints.forEach { e ->
                text(e.id); text(e.serverId); text(e.name); text(e.url); writeBoolean(e.isPrimary); writeBoolean(e.enabled); writeInt(e.sortOrder)
                text(e.lastLatencyMs?.toString()); text(e.lastError); text(e.lastTestedAtEpochMs?.toString())
                text(e.lastApiLatencyMs?.toString()); text(e.lastMediaFirstByteMs?.toString()); text(e.lastMediaThroughputMbps?.toString())
                text(e.lastProtocol); text(e.lastSupportsRange?.toString()); text(e.lastHttpCode?.toString())
            }
        }
        writeInt(snapshot.progress.size)
        snapshot.progress.forEach { p ->
            text(p.serverId); text(p.itemId); writeLong(p.positionMs); writeLong(p.durationMs); writeBoolean(p.isPaused); writeLong(p.updatedAtEpochMs)
            text(p.mode?.name); text(p.itemTitle); text(p.posterUrl); text(p.itemType?.name)
        }
    }
    private fun DataInputStream.snapshot(): BackupSnapshot {
        val servers = List(count()) {
            val id = required(); val name = required(); val type = ServerType.valueOf(required())
            val username = text(); val note = text(); val icon = text(); val default = readBoolean(); val order = readInt(); val created = readLong()
            val connected = text()?.toLong(); val error = text()
            val endpoints = List(count()) {
                ServerEndpoint(required(), required(), required(), required(), readBoolean(), readBoolean(), readInt(),
                    text()?.toLong(), text(), text()?.toLong(), text()?.toLong(), text()?.toLong(), text()?.toDouble(), text(), text()?.toBooleanStrict(), text()?.toInt())
            }
            MediaServer(id, name, type, username, note, icon, default, order, created, connected, error, endpoints)
        }
        val progress = List(count()) {
            PlaybackProgress(required(), required(), readLong(), readLong(), readBoolean(), readLong(),
                mode = text()?.let(PlaybackMode::valueOf), itemTitle = text(), posterUrl = text(), itemType = text()?.let(MediaType::valueOf))
        }
        return BackupSnapshot(servers, progress)
    }
    private fun DataOutputStream.preferences(p: UserPreferences) {
        text(p.playbackEngineMode.name); writeFloat(p.defaultPlaybackSpeed); writeInt(p.subtitleSizeSp)
        writeBoolean(p.enableHardwareDecoding); writeBoolean(p.preferDirectPlay); writeBoolean(p.autoPlayNextEpisode)
        text(p.maxBitrateBps?.toString()); writeBoolean(p.showPlayerInfoOverlay); writeBoolean(p.autoLandscape); writeBoolean(p.immersiveBars)
        p.subtitleStyle.let { s ->
            writeInt(s.textColor); writeInt(s.backgroundColor); writeInt(s.edgeType); writeInt(s.edgeColor)
            writeFloat(s.textScale); writeFloat(s.bottomPaddingFraction); writeBoolean(s.applyEmbeddedStyles)
        }
        p.gestures.let { g ->
            writeBoolean(g.scrubEnabled); writeBoolean(g.doubleTapSeekBackwardEnabled); writeInt(g.doubleTapSeekBackwardSeconds)
            writeBoolean(g.doubleTapSeekForwardEnabled); writeInt(g.doubleTapSeekForwardSeconds); writeBoolean(g.longPressSpeedEnabled)
            writeFloat(g.longPressSpeedMin); writeFloat(g.longPressSpeedMax); writeBoolean(g.longPressDirectionalEnabled); writeFloat(g.longPressDefaultSpeed)
        }
    }
    private fun DataInputStream.preferences() = UserPreferences(
        PlaybackEngineMode.valueOf(required()), readFloat(), readInt(), readBoolean(), readBoolean(), readBoolean(), text()?.toLong(),
        readBoolean(), readBoolean(), readBoolean(),
        SubtitleStyle(readInt(), readInt(), readInt(), readInt(), readFloat(), readFloat(), readBoolean()),
        PlayerGestures(readBoolean(), readBoolean(), readInt(), readBoolean(), readInt(), readBoolean(), readFloat(), readFloat(), readBoolean(), readFloat()),
    )
}
