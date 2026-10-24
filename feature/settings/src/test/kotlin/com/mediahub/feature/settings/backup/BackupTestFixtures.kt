package com.mediahub.feature.settings.backup

import com.mediahub.core.common.backup.BackupCrypto
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.common.backup.BackupFileFormat

/** Unit-test storage; process-restart claims use RestoreSnapshotStoreTest instead. */
internal class MemoryRestoreSnapshotStorage : RestoreSnapshotStorage {
    private val data = mutableMapOf<String, ByteArray>()
    override fun save(id: String, images: RestoreImages) { data[id] = RestoreImageCodec.encode(images) }
    override fun read(id: String): RestoreImages = RestoreImageCodec.decode(requireNotNull(data[id]))
    override fun delete(id: String) { data.remove(id) }
}

/** Deliberately bypass export validation to exercise malformed authenticated import payloads. */
internal fun authenticatedTestBytes(payload: BackupDtos.BackupPayload, password: CharArray): ByteArray {
    val salt = BackupCrypto.randomSalt()
    val key = BackupCrypto.deriveKey(password, salt, 10_000, 256)
    // 信封/AAD 版本必须与载荷 manifest 一致：import 会校验「内外版本一致」(BackupSerializer.kt:273)，
    // 写死 1 会让 v2 载荷被判 Corrupted（task-25 格式版本 2 后暴露）。仍然绕过 export 校验，保持夹具用途不变。
    val version = payload.manifest.formatVersion
    val nonce = BackupCrypto.randomNonce()
    val encrypted = BackupCrypto.encrypt(BackupDtos.encodePayload(payload).toByteArray(), key.key, nonce,
        BackupCrypto.aadFor(BackupCrypto.MAGIC, version, key.params.algorithm, 10_000))
    return BackupFileFormat.encodeEnvelope(BackupFileFormat.Envelope(BackupCrypto.MAGIC, version, key.params.algorithm,
        BackupFileFormat.encodeB64(salt), 10_000, 256, BackupFileFormat.encodeB64(nonce), BackupFileFormat.encodeB64(encrypted))).toByteArray()
}

internal suspend fun BackupRepository.restoreWithFreshPreview(
    validated: ValidatedRestore, strategy: RestoreStrategy, replaceConfirmed: Boolean,
): RestoreResult = restore(validated, strategy, replaceConfirmed, buildPreview(validated, strategy))
