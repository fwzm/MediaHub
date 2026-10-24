package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncContractException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-27：界面接线用到的两件小事 —— 收到的信封原文要随结果回传、acceptOnly 网关一个字节都不写。
 *
 * 真 socket 闭环由 SourceSyncTransferImportTest 覆盖；这里只钉"界面能不能拿到信封"与"接收入口写不写库"。
 */
class SourceSyncTransferImportServiceTest {

    @Test
    fun acceptedEnvelopeIsHandedBackWithItsText() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)
        val bytes = goldenBytes()

        val outcome = service.importEnvelopeBytes(bytes)

        assertTrue(outcome is SourceSyncTransferImportOutcome.Imported)
        val imported = outcome as SourceSyncTransferImportOutcome.Imported
        assertEquals(String(bytes, Charsets.UTF_8), imported.envelopeJson)
        assertEquals(1, gateway.calls)
        assertEquals(imported.envelopeJson, gateway.lastEnvelopeJson)
        assertEquals("device-a", gateway.returned.deviceId)
    }

    @Test
    fun rejectedEnvelopeIsNotHandedToTheGatewayAndCarriesNoText() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)

        val outcome = service.importEnvelopeBytes("{ not a sync document".toByteArray(Charsets.UTF_8))

        assertTrue(outcome is SourceSyncTransferImportOutcome.EnvelopeRejected)
        val rejected = outcome as SourceSyncTransferImportOutcome.EnvelopeRejected
        assertEquals(SourceSyncContractException.MALFORMED_JSON, rejected.reason)
        // 解码都没过，网关一次都不能被叫到 —— 零写入的第一道闸。
        assertEquals(0, gateway.calls)
        assertNull(gateway.lastEnvelopeJson)
    }

    @Test
    fun futureSchemaVersionIsRejectedWithoutCallingTheGateway() = runBlocking {
        val gateway = RecordingGateway()
        val service = SourceSyncTransferImportService(gateway)
        val future = goldenText().replace("\"schemaVersion\":1", "\"schemaVersion\":9")

        val outcome = service.importEnvelopeBytes(future.toByteArray(Charsets.UTF_8))

        val rejected = outcome as SourceSyncTransferImportOutcome.EnvelopeRejected
        assertTrue(rejected.reason.startsWith("unsupported-schema-version"))
        assertEquals(0, gateway.calls)
    }

    @Test
    fun acceptOnlyEchoesIdentityAndContentHashWithoutWriting() = runBlocking {
        val gateway = SourceSyncImportGateways.acceptOnly()

        val first = gateway.importEnvelope(goldenText())
        val second = gateway.importEnvelope(goldenText())

        assertEquals("device-a", first.deviceId)
        assertTrue(!first.snapshotId.isNullOrBlank())
        assertEquals(64, first.envelopeContentHash!!.length)
        assertEquals(first.envelopeContentHash, second.envelopeContentHash)
        assertFalse(first.isRejected)
        assertFalse(first.hasWrites)
        assertEquals(0, first.appliedCount)
        assertEquals(0, first.totalCount)
    }

    @Test
    fun acceptOnlyOnBadJsonReportsTheCodecReasonAndWritesNothing() = runBlocking {
        val gateway = SourceSyncImportGateways.acceptOnly()

        val malformed = gateway.importEnvelope("null")
        val badVersion = gateway.importEnvelope(goldenText().replace("\"schemaVersion\":1", "\"schemaVersion\":9"))

        assertEquals(SourceSyncContractException.MALFORMED_JSON, malformed.rejectedReason)
        assertEquals(0, malformed.appliedCount)
        assertFalse(malformed.hasWrites)
        assertTrue(badVersion.rejectedReason!!.startsWith("unsupported-schema-version"))
        assertFalse(badVersion.hasWrites)
    }

    // ---------------------------------------------------------------- 夹具

    private class RecordingGateway(
        val returned: SourceSyncImportResult = SourceSyncImportResult(
            deviceId = "device-a",
            snapshotId = "snap-incoming",
        ),
    ) : SourceSyncImportGateway {
        var calls = 0
        var lastEnvelopeJson: String? = null

        override suspend fun importEnvelope(envelopeJson: String): SourceSyncImportResult {
            calls++
            lastEnvelopeJson = envelopeJson
            return returned
        }
    }

    private fun goldenBytes(): ByteArray = javaClass.classLoader!!
        .getResourceAsStream("sync/windows-envelope-device-a.json")!!
        .use { it.readBytes() }

    private fun goldenText(): String = String(goldenBytes(), Charsets.UTF_8)
}
