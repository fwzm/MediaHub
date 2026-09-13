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
    val nonce = BackupCrypto.randomNonce()
    val encrypted = BackupCrypto.encrypt(BackupDtos.encodePayload(payload).toByteArray(), key.key, nonce,
        BackupCrypto.aadFor(BackupCrypto.MAGIC, 1, key.params.algorithm, 10_000))
    return BackupFileFormat.encodeEnvelope(BackupFileFormat.Envelope(BackupCrypto.MAGIC, 1, key.params.algorithm,
        BackupFileFormat.encodeB64(salt), 10_000, 256, BackupFileFormat.encodeB64(nonce), BackupFileFormat.encodeB64(encrypted))).toByteArray()
}

internal suspend fun BackupRepository.restoreWithFreshPreview(
    validated: ValidatedRestore, strategy: RestoreStrategy, replaceConfirmed: Boolean,
): RestoreResult = restore(validated, strategy, replaceConfirmed, buildPreview(validated, strategy))
