package com.mediahub.core.database.sync

import com.mediahub.core.common.archive.ArchiveError
import com.mediahub.core.common.archive.ArchiveKdf
import com.mediahub.core.common.archive.ArchiveWriter
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncContractException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-63：离线加密归档（.mharchive）接进导入入口的证据。
 *
 * 三类证据：
 * 1. 跨端：Windows 写出的 1282 B 归档在这里用口令解开，交给网关的明文信封与明文件逐字段相等，
 *    且信封自报不含凭据（canSendSecrets = false）；
 * 2. 稳定码：没口令 passphrase-required；口令错 / 密文被改 wrong-passphrase-or-tampered；
 *    口令太弱 passphrase-too-weak；字节被截断 corrupt-header —— 全部来自 ArchiveError，与 Windows 同名同序；
 * 3. 回归：明文信封的路径一个字节都没变（网关收到的仍是宽松 UTF-8 解码后的原文）。
 */
class SourceSyncArchiveImportTest {

    @Test
    fun looksLikeArchiveOnlyMatchesTheEightByteMagic() {
        assertTrue(SourceSyncDocumentInput.looksLikeArchive(archiveBytes()))
        assertFalse(SourceSyncDocumentInput.looksLikeArchive(ByteArray(0)))
        assertFalse(SourceSyncDocumentInput.looksLikeArchive("MHSYNC".toByteArray(Charsets.US_ASCII)))
        assertFalse(SourceSyncDocumentInput.looksLikeArchive(plainBytes()))
    }

    @Test
    fun windowsArchiveIsDecryptedAndHandedToTheGatewayAsPlainEnvelope() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)

        val outcome = service.importEnvelopeBytes(archiveBytes(), PASSPHRASE)

        assertTrue(outcome is SourceSyncTransferImportOutcome.Imported)
        assertEquals(1, gateway.calls)
        val text = gateway.lastEnvelopeJson!!
        // 网关拿到的必须是明文信封：既不能带 magic，也不能是解码前的二进制。
        assertFalse(text.startsWith("MHSYNCA1"))
        val decoded = SourceSyncCodec.decode(text)
        assertEquals(SourceSyncCodec.decode(goldenText()), decoded)
        assertFalse(decoded.capabilities.canSendSecrets)
        assertEquals(3, decoded.sources.size)
        assertEquals(1, decoded.deletions.size)
    }

    @Test
    fun archiveWithoutAPassphraseIsRejectedWithTheStableCode() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)

        val outcome = service.importEnvelopeBytes(archiveBytes())

        val rejected = outcome as SourceSyncTransferImportOutcome.EnvelopeRejected
        assertEquals(SourceSyncDocumentInput.PASSPHRASE_REQUIRED, rejected.reason)
        // 解码都没开始，网关一次都不能被叫到 —— 零写入的第一道闸。
        assertEquals(0, gateway.calls)
        assertNull(gateway.lastEnvelopeJson)
    }

    @Test
    fun wrongPassphraseReportsTheArchiveCodeAndNeverCallsTheGateway() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)

        val outcome = service.importEnvelopeBytes(archiveBytes(), "a different long passphrase")

        val rejected = outcome as SourceSyncTransferImportOutcome.EnvelopeRejected
        assertEquals(ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED, rejected.reason)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun aTamperedCiphertextReportsTheSameCode() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)
        val tampered = archiveBytes().also { bytes ->
            val last = bytes.size - 1
            bytes[last] = (bytes[last].toInt() xor 0x01).toByte()
        }

        val outcome = service.importEnvelopeBytes(tampered, PASSPHRASE)

        val rejected = outcome as SourceSyncTransferImportOutcome.EnvelopeRejected
        assertEquals(ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED, rejected.reason)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun aPassphraseBelowThePolicyIsRejectedBeforeAnyKeyIsDerived() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)

        val outcome = service.importEnvelopeBytes(archiveBytes(), "short")

        val rejected = outcome as SourceSyncTransferImportOutcome.EnvelopeRejected
        assertEquals(ArchiveError.PASSPHRASE_TOO_WEAK, rejected.reason)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun aTruncatedArchiveReportsItsStructuralCode() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)
        val truncated = archiveBytes().copyOfRange(0, 20)

        val outcome = service.importEnvelopeBytes(truncated, PASSPHRASE)

        val rejected = outcome as SourceSyncTransferImportOutcome.EnvelopeRejected
        assertEquals(ArchiveError.CORRUPT_HEADER, rejected.reason)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun bytesThatOnlyStartWithTheMagicAreRejectedAsACorruptArchive() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)
        val fake = ByteArray(16)
        "MHSYNCA1".toByteArray(Charsets.US_ASCII).copyInto(fake)

        val outcome = service.importEnvelopeBytes(fake, PASSPHRASE)

        val rejected = outcome as SourceSyncTransferImportOutcome.EnvelopeRejected
        assertEquals(ArchiveError.CORRUPT_HEADER, rejected.reason)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun bytesShorterThanTheMagicAreTreatedAsPlaintext() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)

        val outcome = service.importEnvelopeBytes("MHSYNC".toByteArray(Charsets.US_ASCII))

        val rejected = outcome as SourceSyncTransferImportOutcome.EnvelopeRejected
        assertEquals(SourceSyncContractException.MALFORMED_JSON, rejected.reason)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun plaintextEnvelopeKeepsTheExactTextPath() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)
        val bytes = plainBytes()

        val outcome = service.importEnvelopeBytes(bytes)

        val imported = outcome as SourceSyncTransferImportOutcome.Imported
        assertEquals(String(bytes, Charsets.UTF_8), imported.envelopeJson)
        assertEquals(imported.envelopeJson, gateway.lastEnvelopeJson)
        assertEquals(1, gateway.calls)
    }

    @Test
    fun aLocallyWrittenArchiveRoundTripsThroughTheSamePath() = runBlocking {
        val original = SourceSyncCodec.decode(goldenText())
        val written = ArchiveWriter.write(
            original,
            PASSPHRASE,
            ArchiveKdf.DEFAULT_ITERATIONS,
            createdAtEpochMs = 1_700_000_000_000L,
        )
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)

        val outcome = service.importEnvelopeBytes(written, PASSPHRASE)

        assertTrue(outcome is SourceSyncTransferImportOutcome.Imported)
        assertEquals(original, SourceSyncCodec.decode(gateway.lastEnvelopeJson!!))
    }

    @Test
    fun acceptOnlyGatewayEchoesTheDecryptedIdentityWithoutWriting() = runBlocking {
        val service = SourceSyncTransferImportService(SourceSyncImportGateways.acceptOnly())

        val outcome = service.importEnvelopeBytes(archiveBytes(), PASSPHRASE)

        val imported = outcome as SourceSyncTransferImportOutcome.Imported
        assertEquals("device-a", imported.result.deviceId)
        assertEquals("1b96e7eb417e4caeb27eb60f3d1acfc3", imported.result.snapshotId)
        assertEquals(64, imported.result.envelopeContentHash!!.length)
        assertFalse(imported.result.isRejected)
        // W6-97：回执类型里已经没有写入计数（接收通道装不下计数）；「同一封信封走写库网关会写两条」
        // 的对照在 SourceSyncReceiveWiringTest 里，这里只钉身份回显。
    }

    // ---------------------------------------------------------------- 夹具

    private class RecordingGateway : SourceSyncImportGateway {
        var calls = 0
        var lastEnvelopeJson: String? = null

        override suspend fun importEnvelope(envelopeJson: String): SourceSyncReceiveEcho {
            calls++
            lastEnvelopeJson = envelopeJson
            return SourceSyncReceiveEcho(deviceId = "device-a", snapshotId = "snap-incoming")
        }
    }

    private fun archiveBytes(): ByteArray = javaClass.classLoader!!
        .getResourceAsStream("sync/windows-archive-device-a.mharchive")!!
        .use { it.readBytes() }

    private fun plainBytes(): ByteArray = javaClass.classLoader!!
        .getResourceAsStream("sync/windows-envelope-device-a.json")!!
        .use { it.readBytes() }

    private fun goldenText(): String = String(plainBytes(), Charsets.UTF_8)

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
    }
}