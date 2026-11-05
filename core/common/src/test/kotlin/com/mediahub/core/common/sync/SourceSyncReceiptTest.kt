package com.mediahub.core.common.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-40：回执帧（0x04）的四码、选项、写/等两侧语义，以及「默认关闭时字节不变」。
 *
 * 这些用例不碰 socket：真 socket 上的回执由 SourceSyncTransferListenerTest（监听端）
 * 与 core:database 的 SourceSyncListenerImportTest / SourceSyncListenerInteropTest 钉住。
 */
class SourceSyncReceiptTest {

    @Test
    fun reasonsAreTheFrozenFourInOrder() {
        assertEquals(
            listOf(
                "receipt-missing",
                "receipt-timeout",
                "receipt-invalid",
                "receipt-not-enabled",
            ),
            SourceSyncReceiptReasons.ALL,
        )
        assertEquals("receipt-missing", SourceSyncReceiptReasons.RECEIPT_MISSING)
        assertEquals("receipt-timeout", SourceSyncReceiptReasons.RECEIPT_TIMEOUT)
        assertEquals("receipt-invalid", SourceSyncReceiptReasons.RECEIPT_INVALID)
        assertEquals("receipt-not-enabled", SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED)
    }

    @Test
    fun receiptCodesDoNotOverlapTheOtherThreeTables() {
        val others = SourceSyncTransferReasons.ALL +
            SourceSyncTransferAuthReasons.ALL +
            SourceSyncTlsReasons.ALL
        assertEquals(SourceSyncTransferReasons.ALL.size + SourceSyncTransferAuthReasons.ALL.size + SourceSyncTlsReasons.ALL.size, others.size)
        for (code in SourceSyncReceiptReasons.ALL) {
            assertTrue("回执码必须以 receipt- 开头：" + code, code.startsWith("receipt-"))
            assertFalse("回执码不得与其它码表重叠：" + code, others.contains(code))
        }
        assertEquals(SourceSyncReceiptReasons.ALL.size, SourceSyncReceiptReasons.ALL.toSet().size)
    }

    @Test
    fun defaultOptionsAreOffAndSaySo() {
        assertFalse(SourceSyncReceiptOptions.Off.enabled)
        assertEquals(0, SourceSyncReceiptOptions.Off.budgetMs)
        assertEquals("SourceSyncReceiptOptions { Enabled = false }", SourceSyncReceiptOptions.Off.toString())
    }

    @Test
    fun writingIsTheReceiverRoleAndSaysWhatItDoes() {
        val writing = SourceSyncReceiptOptions.writing()
        assertTrue(writing.enabled)
        assertEquals(SourceSyncReceiptOptions.DEFAULT_BUDGET_MS, writing.budgetMs)
        assertEquals("SourceSyncReceiptOptions { Enabled = true, Role = write }", writing.toString())
    }

    @Test
    fun awaitingFallsBackToTheDefaultBudget() {
        assertEquals(2_000, SourceSyncReceiptOptions.DEFAULT_BUDGET_MS)
        assertEquals(2_000, SourceSyncReceiptOptions.awaiting().budgetMs)
        assertEquals(2_000, SourceSyncReceiptOptions.awaiting(null).budgetMs)
        assertEquals(2_000, SourceSyncReceiptOptions.awaiting(0).budgetMs)
        assertEquals(2_000, SourceSyncReceiptOptions.awaiting(-5).budgetMs)
    }

    @Test
    fun awaitingKeepsAPositiveBudgetVerbatim() {
        val awaiting = SourceSyncReceiptOptions.awaiting(250)
        assertTrue(awaiting.enabled)
        assertEquals(250, awaiting.budgetMs)
        assertEquals("SourceSyncReceiptOptions { Enabled = true, Role = await, BudgetMs = 250 }", awaiting.toString())
    }

    @Test
    fun booleanHelpersMapToOffOrOn() {
        assertFalse(SourceSyncReceiptOptions.writingOrOff(false).enabled)
        assertTrue(SourceSyncReceiptOptions.writingOrOff(true).enabled)
        assertFalse(SourceSyncReceiptOptions.awaitingOrOff(false, 500).enabled)
        assertEquals(500, SourceSyncReceiptOptions.awaitingOrOff(true, 500).budgetMs)
        assertEquals(
            SourceSyncReceiptOptions.DEFAULT_BUDGET_MS,
            SourceSyncReceiptOptions.awaitingOrOff(true).budgetMs,
        )
    }

    @Test
    fun confirmedCarriesTheReceiptFrameSize() {
        val result = SourceSyncReceiptResult.confirmed(7L)
        assertTrue(result.confirmed)
        assertNull(result.reason)
        assertEquals(SourceSyncReceipt.RECEIPT_FRAME_BYTES, result.bytesRead)
        assertEquals(1, SourceSyncReceipt.RECEIPT_FRAME_BYTES)
        assertEquals(0x04, SourceSyncReceipt.RECEIPT_FRAME_KIND)
        assertEquals(7L, result.elapsedMs)
        assertFalse(result.isSilent)
    }

    @Test
    fun failedKeepsTheCodeAndTheDetail() {
        val result = SourceSyncReceiptResult.failed(SourceSyncReceiptReasons.RECEIPT_MISSING, "peer closed", 12L)
        assertFalse(result.confirmed)
        assertEquals(SourceSyncReceiptReasons.RECEIPT_MISSING, result.reason)
        assertEquals("peer closed", result.detail)
        assertEquals(0, result.bytesRead)
        assertFalse(result.isSilent)
    }

    @Test
    fun silentIsDeliberateAndNotAFailure() {
        val result = SourceSyncReceiptResult.silent("gate refused")
        assertFalse(result.confirmed)
        assertNull(result.reason)
        assertTrue(result.isSilent)
        assertEquals("gate refused", result.detail)
    }

    @Test
    fun resultNeverPrintsTheDetailTwice() {
        val text = SourceSyncReceiptResult.confirmed(3L).toString()
        assertTrue(text, text.contains("Confirmed = true"))
        assertTrue(text, text.contains("BytesRead = 1"))
        assertTrue(text, text.contains("ElapsedMs = 3"))
    }

    @Test
    fun writeEmitsExactlyOneReceiptByte() {
        val output = ByteArrayOutputStream()
        val result = SourceSyncReceipt.write(output)
        assertTrue(result.confirmed)
        assertEquals(1, result.bytesRead)
        assertArrayEquals(byteArrayOf(0x04), output.toByteArray())
    }

    @Test
    fun writeWithoutAStreamIsNotEnabled() {
        val result = SourceSyncReceipt.write(null)
        assertFalse(result.confirmed)
        assertEquals(SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED, result.reason)
    }

    @Test
    fun writeToABrokenStreamIsNotEnabled() {
        val result = SourceSyncReceipt.write(BrokenOutputStream())
        assertFalse(result.confirmed)
        assertEquals(SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED, result.reason)
        assertFalse(result.detail.isNullOrBlank())
    }

    @Test
    fun awaitConfirmsOnAReceiptByteAndReadsNoFurther() {
        val input = ByteArrayInputStream(byteArrayOf(0x04, 0x55))
        val result = SourceSyncReceipt.await(input, 1_000)
        assertTrue(result.confirmed)
        assertEquals(1, result.bytesRead)
        assertEquals("只允许读一个字节", 1, input.available())
    }

    @Test
    fun awaitWithoutAStreamIsNotEnabled() {
        val result = SourceSyncReceipt.await(null)
        assertEquals(SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED, result.reason)
    }

    @Test
    fun awaitReportsMissingWhenThePeerClosesWithoutAReceipt() {
        val result = SourceSyncReceipt.await(ByteArrayInputStream(ByteArray(0)), 1_000)
        assertFalse(result.confirmed)
        assertEquals(SourceSyncReceiptReasons.RECEIPT_MISSING, result.reason)
        assertTrue(result.detail.orEmpty(), result.detail.orEmpty().contains("回执字节"));
    }

    @Test
    fun awaitReportsInvalidForAnotherByte() {
        val result = SourceSyncReceipt.await(ByteArrayInputStream(byteArrayOf(0x02)), 1_000)
        assertFalse(result.confirmed)
        assertEquals(SourceSyncReceiptReasons.RECEIPT_INVALID, result.reason)
        assertTrue(result.detail.orEmpty(), result.detail.orEmpty().contains("0x2"));
    }

    @Test
    fun awaitReportsTimeoutWithTheBudget() {
        val result = SourceSyncReceipt.await(TimeoutInputStream(), 1_500)
        assertFalse(result.confirmed)
        assertEquals(SourceSyncReceiptReasons.RECEIPT_TIMEOUT, result.reason)
        assertTrue(result.detail.orEmpty(), result.detail.orEmpty().contains("1500"));
    }

    @Test
    fun awaitReportsMissingOnAnIoError() {
        val result = SourceSyncReceipt.await(BrokenInputStream(), 1_000)
        assertFalse(result.confirmed)
        assertEquals(SourceSyncReceiptReasons.RECEIPT_MISSING, result.reason)
    }

    @Test
    fun acknowledgeWritesWhenTheGateAccepts() {
        val output = ByteArrayOutputStream()
        val result = SourceSyncReceipt.acknowledge(output, PAYLOAD, SourceSyncReceiptOptions.writing()) { true }
        assertTrue(result.confirmed)
        assertArrayEquals(byteArrayOf(0x04), output.toByteArray())
    }

    @Test
    fun acknowledgeStaysSilentWhenTheGateRefuses() {
        val output = ByteArrayOutputStream()
        val result = SourceSyncReceipt.acknowledge(output, PAYLOAD, SourceSyncReceiptOptions.writing()) { false }
        assertTrue(result.isSilent)
        assertEquals(0, output.size())
    }

    @Test
    fun acknowledgeIsSilentWhenReceiptsAreOff() {
        val output = ByteArrayOutputStream()
        val result = SourceSyncReceipt.acknowledge(output, PAYLOAD, SourceSyncReceiptOptions.Off) { true }
        assertTrue(result.isSilent)
        assertEquals(0, output.size())
    }

    @Test
    fun acknowledgeWithoutAStreamIsNotEnabled() {
        val result = SourceSyncReceipt.acknowledge(null, PAYLOAD, SourceSyncReceiptOptions.writing())
        assertEquals(SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED, result.reason)
    }

    @Test
    fun sendWithReceiptsOffWritesTheFrozenBytes() {
        val output = ByteArrayOutputStream()
        val result = SourceSyncTransfer.sendAndAwaitReceipt(
            output = output,
            input = ByteArrayInputStream(ByteArray(0)),
            envelope = PAYLOAD,
            receipt = SourceSyncReceiptOptions.Off,
        )
        assertTrue(result is SourceSyncTransferResult.Ok)
        assertArrayEquals(SourceSyncTransfer.encode(PAYLOAD), output.toByteArray())
        assertEquals(SourceSyncTransfer.chunkCount(PAYLOAD.size), (result as SourceSyncTransferResult.Ok).frameCount)
    }

    @Test
    fun sendAwaitingReceiptConfirmsOnTheReceiptByte() {
        val output = ByteArrayOutputStream()
        val result = SourceSyncTransfer.sendAndAwaitReceipt(
            output = output,
            input = ByteArrayInputStream(byteArrayOf(0x04)),
            envelope = PAYLOAD,
            receipt = SourceSyncReceiptOptions.awaiting(500),
        )
        assertTrue(result is SourceSyncTransferResult.Ok)
        assertArrayEquals(SourceSyncTransfer.encode(PAYLOAD), output.toByteArray())
    }

    @Test
    fun sendAwaitingReceiptReportsMissingAfterWritingEveryFrame() {
        val output = ByteArrayOutputStream()
        val result = SourceSyncTransfer.sendAndAwaitReceipt(
            output = output,
            input = ByteArrayInputStream(ByteArray(0)),
            envelope = PAYLOAD,
            receipt = SourceSyncReceiptOptions.awaiting(500),
        )
        val failed = result as SourceSyncTransferResult.Failed
        assertEquals(SourceSyncReceiptReasons.RECEIPT_MISSING, failed.reason)
        assertEquals("回执失败不得回滚已经写出的帧", SourceSyncTransfer.encode(PAYLOAD).size, output.size())
    }

    @Test
    fun sendAwaitingReceiptWithoutAnInputIsNotEnabled() {
        val output = ByteArrayOutputStream()
        val result = SourceSyncTransfer.sendAndAwaitReceipt(
            output = output,
            input = null,
            envelope = PAYLOAD,
            receipt = SourceSyncReceiptOptions.awaiting(500),
        )
        assertEquals(
            SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED,
            (result as SourceSyncTransferResult.Failed).reason,
        )
        assertEquals(SourceSyncTransfer.encode(PAYLOAD).size, output.size())
    }

    @Test
    fun sendAwaitingReceiptReportsTimeoutSeparately() {
        val result = SourceSyncTransfer.sendAndAwaitReceipt(
            output = ByteArrayOutputStream(),
            input = TimeoutInputStream(),
            envelope = PAYLOAD,
            receipt = SourceSyncReceiptOptions.awaiting(500),
        )
        assertEquals(
            SourceSyncReceiptReasons.RECEIPT_TIMEOUT,
            (result as SourceSyncTransferResult.Failed).reason,
        )
    }

    @Test
    fun sendAwaitingReceiptRejectsAnUnknownByte() {
        val result = SourceSyncTransfer.sendAndAwaitReceipt(
            output = ByteArrayOutputStream(),
            input = ByteArrayInputStream(byteArrayOf(0x7F)),
            envelope = PAYLOAD,
            receipt = SourceSyncReceiptOptions.awaiting(500),
        )
        assertEquals(
            SourceSyncReceiptReasons.RECEIPT_INVALID,
            (result as SourceSyncTransferResult.Failed).reason,
        )
    }

    @Test
    fun sendAwaitingReceiptFailsWhenTheTimeoutCannotBeSet() {
        val output = ByteArrayOutputStream()
        val result = SourceSyncTransfer.sendAndAwaitReceipt(
            output = output,
            input = ByteArrayInputStream(byteArrayOf(0x04)),
            envelope = PAYLOAD,
            receipt = SourceSyncReceiptOptions.awaiting(500),
            readTimeoutSetter = { throw IllegalStateException("closed") },
        )
        assertEquals(
            SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED,
            (result as SourceSyncTransferResult.Failed).reason,
        )
        assertEquals(SourceSyncTransfer.encode(PAYLOAD).size, output.size())
    }

    @Test
    fun sendAwaitingReceiptAppliesTheBudgetToTheSocketTimeout() {
        val seen = ArrayList<Int>()
        val result = SourceSyncTransfer.sendAndAwaitReceipt(
            output = ByteArrayOutputStream(),
            input = ByteArrayInputStream(byteArrayOf(0x04)),
            envelope = PAYLOAD,
            receipt = SourceSyncReceiptOptions.awaiting(250),
            readTimeoutSetter = { millis -> seen.add(millis) },
        )
        assertTrue(result is SourceSyncTransferResult.Ok)
        assertEquals(listOf(250), seen)
    }

    @Test
    fun sendAwaitingReceiptStillRejectsAnEmptyEnvelope() {
        val output = ByteArrayOutputStream()
        val seen = ArrayList<Int>()
        val result = SourceSyncTransfer.sendAndAwaitReceipt(
            output = output,
            input = ByteArrayInputStream(byteArrayOf(0x04)),
            envelope = ByteArray(0),
            receipt = SourceSyncReceiptOptions.awaiting(500),
            readTimeoutSetter = { millis -> seen.add(millis) },
        )
        assertEquals(SourceSyncTransferReasons.PAYLOAD_TOO_LARGE, (result as SourceSyncTransferResult.Failed).reason)
        assertEquals(0, output.size())
        assertTrue("前置拒绝时不得改读超时", seen.isEmpty())
    }

    private class BrokenOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw IOException("broken pipe")
        }
    }

    private class TimeoutInputStream : InputStream() {
        override fun read(): Int {
            throw SocketTimeoutException("read timed out")
        }
    }

    private class BrokenInputStream : InputStream() {
        override fun read(): Int {
            throw IOException("connection reset")
        }
    }

    private companion object {
        val PAYLOAD: ByteArray = "w6-40-receipt-envelope".toByteArray(Charsets.UTF_8)
    }
}
