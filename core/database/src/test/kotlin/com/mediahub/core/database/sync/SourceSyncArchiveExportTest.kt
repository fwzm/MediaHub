package com.mediahub.core.database.sync

import com.mediahub.core.common.archive.ArchiveError
import com.mediahub.core.common.archive.ArchiveKdf
import com.mediahub.core.common.archive.ArchiveReader
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncSourceTypeValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-64：本机导出落成文件时的三种产物（明文 / 加密归档 / 拒绝）的证据。
 *
 * 三类证据：
 * 1. 明文：口令为 null 时原样交出导出 JSON，不重新编码（与发送卡同一份文本）；
 * 2. 归档：口令非空时由 ArchiveWriter 包成 .mharchive，读回来与导出信封逐字段相等，
 *    归档头的时间戳取信封自己的时间戳、迭代数取默认 600000（对齐 Windows Export 的 now 语义）；
 * 3. 拒绝：弱口令 / 空白口令 / 自报携带凭据全部折成 ArchiveError 稳定码，一个都不改写，
 *    且空白口令不会被当成「明文导出」悄悄放行（null 与空串是两件事）。
 *
 * 归档字节自身的读端语义已由 SourceSyncArchiveImportTest 用 Windows 写出的真归档钉住；
 * 这里只证明本端写出来的东西本端读得回来、且字段没有走样。
 */
class SourceSyncArchiveExportTest {

    @Test
    fun plaintextExportKeepsTheExportedJsonWithoutReEncoding() {
        val export = export()

        val plain = SourceSyncArchiveExport.of(export, passphrase = null) as SourceSyncArchiveArtifact.Plain

        assertEquals(export.json, plain.json);
        assertEquals(1, plain.sourceCount);
        assertEquals(0, plain.skippedCount);
    }

    @Test
    fun aPassphraseProducesAnArchiveThatReadsBackAsTheSameEnvelope() {
        val export = export();

        val sealed = SourceSyncArchiveExport.of(export, PASSPHRASE) as SourceSyncArchiveArtifact.Sealed

        assertEquals("MHSYNCA1", String(sealed.bytes.copyOfRange(0, 8), Charsets.US_ASCII));
        val (decoded, reason) = ArchiveReader.tryRead(sealed.bytes, PASSPHRASE);
        assertNull(reason);
        assertEquals(export.envelope, decoded);
        assertEquals(1, sealed.sourceCount);
    }

    @Test
    fun theArchiveHeaderStampsTheEnvelopeTimestampAndTheDefaultIterations() {
        val export = export();
        val sealed = SourceSyncArchiveExport.of(export, PASSPHRASE) as SourceSyncArchiveArtifact.Sealed

        val header = ArchiveReader.readHeader(sealed.bytes);

        // 归档头与信封共用一个时间戳：Windows Export 的 now 语义，避免两个时间互相矛盾。
        assertEquals(export.envelope.createdAtEpochMs, header.createdAtEpochMs);
        assertEquals(export.envelope.createdAtEpochMs, sealed.createdAtEpochMs);
        assertEquals(ArchiveKdf.DEFAULT_ITERATIONS, header.iterations);
        assertEquals(ArchiveKdf.DEFAULT_ITERATIONS, sealed.iterations);
        assertEquals(ArchiveKdf.PBKDF2_SHA256_NAME, header.kdf);
        assertEquals(1, header.version);
    }

    @Test
    fun aPassphraseBelowThePolicyIsRejectedWithTheArchiveCode() {
        val rejected = SourceSyncArchiveExport.of(export(), "short") as SourceSyncArchiveArtifact.Rejected

        assertEquals(ArchiveError.PASSPHRASE_TOO_WEAK, rejected.reason);
        assertTrue((rejected.message ?: "").isNotEmpty());
    }

    @Test
    fun aBlankPassphraseIsRejectedInsteadOfFallingBackToPlaintext() {
        // null 才是「明文导出」；空白串是调用方搞错了，必须报错而不是悄悄写明文。
        val rejected = SourceSyncArchiveExport.of(export(), "   ") as SourceSyncArchiveArtifact.Rejected

        assertEquals(ArchiveError.PASSPHRASE_EMPTY, rejected.reason);
    }

    @Test
    fun anEnvelopeThatClaimsToCarryCredentialsIsRejected() {
        val rejected = SourceSyncArchiveExport.of(export(canSendSecrets = true), PASSPHRASE)
            as SourceSyncArchiveArtifact.Rejected

        assertEquals(ArchiveError.SECRETS_NOT_ALLOWED, rejected.reason);
    }

    @Test
    fun skippedSourcesAreCountedAndDoNotBlockEitherFlavour() {
        val export = export(skipped = listOf("server-1", "server-2"));

        val plain = SourceSyncArchiveExport.of(export, null) as SourceSyncArchiveArtifact.Plain
        val sealed = SourceSyncArchiveExport.of(export, PASSPHRASE) as SourceSyncArchiveArtifact.Sealed

        assertEquals(2, plain.skippedCount);
        assertEquals(2, sealed.skippedCount);
        assertEquals(1, plain.sourceCount);
        assertEquals(1, sealed.sourceCount);
    }

    @Test
    fun anExportWithNoSourcesStillProducesAReadableArchive() {
        // 空导出也要能落盘：读端不能因为 sources 为空就判成坏文件。
        val export = export(sources = emptyList());
        val sealed = SourceSyncArchiveExport.of(export, PASSPHRASE) as SourceSyncArchiveArtifact.Sealed

        val (decoded, reason) = ArchiveReader.tryRead(sealed.bytes, PASSPHRASE);
        assertNull(reason);
        assertEquals(0, sealed.sourceCount);
        assertTrue(decoded!!.sources.isEmpty());
        assertFalse(decoded.capabilities.canSendSecrets);
    }

    // ---------------------------------------------------------------- 夹具

    private fun export(
        canSendSecrets: Boolean = false,
        sources: List<SourceRecord> = listOf(record("sync-1")),
        skipped: List<String> = emptyList(),
    ): SourceSyncExport {
        val envelope = SourceSyncEnvelope(
            deviceId = "device-a",
            snapshotId = "snap-export",
            createdAtEpochMs = 1_700_000_000_000L,
            capabilities = SyncCapabilities(canSendSecrets = canSendSecrets, sourceTypes = listOf("emby")),
            sources = sources,
        )
        return SourceSyncExport(
            envelope = envelope,
            json = SourceSyncCodec.encode(envelope),
            skippedServerIds = skipped,
        )
    }

    private fun record(id: String): SourceRecord = SourceRecord(
        syncSourceId = id,
        sourceType = SyncSourceTypeValue.parse("emby"),
        displayName = "客厅 Emby",
        endpoints = listOf(SourceEndpoint(url = "http://192.168.1.10:8096", isPrimary = true)),
    )

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
    }
}
