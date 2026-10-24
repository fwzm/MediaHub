package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SourceSyncSummary
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.core.database.sync.SourceSyncImportResult
import com.mediahub.core.database.sync.SourceSyncPreview
import com.mediahub.core.database.sync.SourceSyncTransferImportOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-27「从 Windows 接收」的纯 JVM 证据。
 *
 * 不拨号、不起 socket（真 socket 路径由 core:database 的 SourceSyncTransferImportTest 钉住）：
 * 这里断言的只有界面层会做什么 —— 失败零写入、成功不自动预览/应用、码原样透出、连点只发一次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSyncReceiveControllerTest {

    // ---------------------------------------------------------------- 表单：默认值与端口校验

    @Test
    fun defaultsAreSafe() {
        val form = SourceSyncReceiveForm()

        assertEquals("127.0.0.1", form.host)
        assertEquals("", form.port)
        assertEquals("", form.challenge)
        assertFalse(form.canSubmit)
        assertEquals("127.0.0.1", form.hostOrLoopback)
    }

    @Test
    fun blankPortSubmitsNothing() = runTest {
        val port = FakeReceivePort(importedOutcome())
        var loads = 0
        val controller = SourceSyncReceiveController(port) { _, _ -> loads++ }

        val submitted = controller.receive(SourceSyncReceiveForm(port = "   "))

        assertFalse(submitted)
        assertEquals(0, port.calls)
        assertEquals(SourceSyncReceiveState.Idle, controller.state.value)
        assertEquals(0, loads)
    }

    @Test
    fun outOfRangeAndNonNumericPortsSubmitNothing() = runTest {
        val invalid = listOf("0", "65536", "-1", "abc", "9000.5", " 0x10")
        invalid.forEach { raw ->
            val port = FakeReceivePort(importedOutcome())
            val controller = SourceSyncReceiveController(port) { _, _ -> }

            assertFalse(controller.receive(SourceSyncReceiveForm(port = raw)))
            assertNull(SourceSyncReceiveForm(port = raw).portNumber)
            assertEquals(0, port.calls)
        }
    }

    @Test
    fun portBoundariesAreAccepted() {
        assertEquals(1, SourceSyncReceiveForm(port = "1").portNumber)
        assertEquals(65535, SourceSyncReceiveForm(port = "65535").portNumber)
        assertTrue(SourceSyncReceiveForm(port = " 8942 ").canSubmit)
        assertEquals(8942, SourceSyncReceiveForm(port = " 8942 ").portNumber)
    }

    @Test
    fun blankHostFallsBackToLoopback() = runTest {
        val port = FakeReceivePort(importedOutcome())
        val controller = SourceSyncReceiveController(port) { _, _ -> }

        assertTrue(controller.receive(SourceSyncReceiveForm(host = "   ", port = "8942")))

        assertEquals("127.0.0.1", port.host)
    }

    @Test
    fun blankChallengeIsPassedAsNull() = runTest {
        val port = FakeReceivePort(importedOutcome())
        val controller = SourceSyncReceiveController(port) { _, _ -> }

        controller.receive(SourceSyncReceiveForm(port = "8942", challenge = "   "))

        assertNull(port.challenge)
    }

    @Test
    fun challengeIsTrimmedAndForwardedVerbatim() = runTest {
        val port = FakeReceivePort(importedOutcome())
        val controller = SourceSyncReceiveController(port) { _, _ -> }

        controller.receive(SourceSyncReceiveForm(port = "8942", challenge = "  transfer-vector-challenge-01  "))

        assertEquals("transfer-vector-challenge-01", port.challenge)
    }

    // ---------------------------------------------------------------- 成功：只送信封，不预览不应用

    @Test
    fun importedEnvelopeEntersSessionWithoutPreviewOrApply() = runTest {
        val seeded = seededSession()
        val port = FakeReceivePort(importedOutcome())
        val controller = SourceSyncReceiveController(port) { json, name ->
            seeded.session.loadEnvelope(json, name)
        }

        assertTrue(controller.receive(SourceSyncReceiveForm(port = "8942")))

        val state = seeded.session.state.value
        assertEquals(SourceSyncImportStatus.READY_TO_PREVIEW, state.status)
        assertEquals(envelopeJson(), state.envelopeJson)
        assertNull(state.preview)
        assertNull(state.applied)
        // 种子时预览过一次；接收**没有**再预览、也没有应用。
        assertEquals(1, seeded.preview.calls)
        assertEquals(0, seeded.apply.calls)
    }

    @Test
    fun importedEnvelopeCarriesTheTransferFileName() = runTest {
        val session = SourceSyncImportSession(FakePreviewPort(ready(plan())), FakeApplyPort())
        val controller = SourceSyncReceiveController(FakeReceivePort(importedOutcome())) { json, name ->
            session.loadEnvelope(json, name)
        }

        controller.receive(SourceSyncReceiveForm(port = "8942"))

        assertEquals(SourceSyncReceiveController.TRANSFER_FILE_NAME, session.state.value.fileName)
        assertEquals("windows-transfer.envelope.json", session.state.value.fileName)
    }

    @Test
    fun importedReportsDeviceIdInItsOwnState() = runTest {
        val controller = SourceSyncReceiveController(FakeReceivePort(importedOutcome(deviceId = "device-b"))) { _, _ -> }

        controller.receive(SourceSyncReceiveForm(port = "8942"))

        val state = controller.state.value
        assertTrue(state is SourceSyncReceiveState.Received)
        assertEquals("device-b", (state as SourceSyncReceiveState.Received).deviceId)
        assertNull(SourceSyncReceiveCopy.codeOf(state))
    }

    // ---------------------------------------------------------------- 失败：零写入、码原样

    @Test
    fun closedPortKeepsSessionUntouched() = runTest {
        val seeded = seededSession()
        val before = seeded.session.state.value
        val controller = SourceSyncReceiveController(
            FakeReceivePort(SourceSyncTransferImportOutcome.TransferFailed("connect-failed", "Connection refused")),
        ) { json, name -> seeded.session.loadEnvelope(json, name) }

        controller.receive(SourceSyncReceiveForm(port = "8942"))

        assertEquals(before, seeded.session.state.value)
        assertEquals(1, seeded.preview.calls)
        assertEquals(0, seeded.apply.calls)
        val state = controller.state.value
        assertEquals("connect-failed", SourceSyncReceiveCopy.codeOf(state))
        assertTrue(SourceSyncReceiveCopy.lineOf(state)!!.contains("Connection refused"))
    }

    @Test
    fun notLoopbackKeepsSessionUntouched() = runTest {
        val seeded = seededSession()
        val before = seeded.session.state.value
        val controller = SourceSyncReceiveController(
            FakeReceivePort(SourceSyncTransferImportOutcome.TransferFailed("not-loopback")),
        ) { json, name -> seeded.session.loadEnvelope(json, name) }

        controller.receive(SourceSyncReceiveForm(host = "10.1.2.3", port = "8942"))

        assertEquals("not-loopback", SourceSyncReceiveCopy.codeOf(controller.state.value))
        // 非回环是传输客户端的判定：界面只显示它的码，不自己换成别的说法。
        assertTrue(controller.state.value is SourceSyncReceiveState.Failed)
        assertEquals(before, seeded.session.state.value)
        assertEquals(0, seeded.apply.calls)
    }

    @Test
    fun authMismatchKeepsSessionUntouched() = runTest {
        val seeded = seededSession()
        val before = seeded.session.state.value
        val controller = SourceSyncReceiveController(
            FakeReceivePort(
                SourceSyncTransferImportOutcome.TransferFailed("auth-mismatch", "tag verification failed"),
            ),
        ) { json, name -> seeded.session.loadEnvelope(json, name) }

        controller.receive(SourceSyncReceiveForm(port = "8942", challenge = "expected"))

        assertEquals("auth-mismatch", SourceSyncReceiveCopy.codeOf(controller.state.value))
        assertEquals(before, seeded.session.state.value)
    }

    @Test
    fun envelopeRejectedKeepsSessionUntouched() = runTest {
        val seeded = seededSession()
        val before = seeded.session.state.value
        val controller = SourceSyncReceiveController(
            FakeReceivePort(SourceSyncTransferImportOutcome.EnvelopeRejected("malformed-json", "not a sync document")),
        ) { json, name -> seeded.session.loadEnvelope(json, name) }

        controller.receive(SourceSyncReceiveForm(port = "8942"))

        val state = controller.state.value
        assertTrue(state is SourceSyncReceiveState.Rejected)
        assertEquals("malformed-json", (state as SourceSyncReceiveState.Rejected).reason)
        assertEquals("not a sync document", state.message)
        assertEquals(before, seeded.session.state.value)
    }

    @Test
    fun rejectedImportResultWritesNothing() = runTest {
        val seeded = seededSession()
        val before = seeded.session.state.value
        val rejected = SourceSyncTransferImportOutcome.Imported(
            SourceSyncImportResult(rejectedReason = "duplicate-sync-source-id", rejectedMessage = "same id twice"),
            envelopeJson(),
        )
        val controller = SourceSyncReceiveController(FakeReceivePort(rejected)) { json, name ->
            seeded.session.loadEnvelope(json, name)
        }

        controller.receive(SourceSyncReceiveForm(port = "8942"))

        assertEquals("duplicate-sync-source-id", SourceSyncReceiveCopy.codeOf(controller.state.value))
        assertEquals(before, seeded.session.state.value)
    }

    @Test
    fun importedWithoutEnvelopeJsonIsALocalFailure() = runTest {
        val seeded = seededSession()
        val before = seeded.session.state.value
        val noJson = SourceSyncTransferImportOutcome.Imported(SourceSyncImportResult(deviceId = "device-a"))
        val controller = SourceSyncReceiveController(FakeReceivePort(noJson)) { json, name ->
            seeded.session.loadEnvelope(json, name)
        }

        controller.receive(SourceSyncReceiveForm(port = "8942"))

        val state = controller.state.value
        assertEquals(SourceSyncReceiveController.RECEIVE_FAILED, SourceSyncReceiveCopy.codeOf(state))
        assertEquals(SourceSyncReceiveController.MISSING_ENVELOPE, (state as SourceSyncReceiveState.Failed).detail)
        assertEquals(before, seeded.session.state.value)
    }

    @Test
    fun portFailureAfterSuccessDoesNotKeepTheOldDevice() = runTest {
        val port = FakeReceivePort(importedOutcome(deviceId = "device-a"))
        val controller = SourceSyncReceiveController(port) { _, _ -> }
        controller.receive(SourceSyncReceiveForm(port = "8942"))
        assertEquals("device-a", (controller.state.value as SourceSyncReceiveState.Received).deviceId)

        port.outcome = SourceSyncTransferImportOutcome.TransferFailed("transfer-timeout", "no data")
        controller.receive(SourceSyncReceiveForm(port = "8943"))

        val state = controller.state.value
        assertTrue(state is SourceSyncReceiveState.Failed)
        // 上一次成功留下的 deviceId 不得跟着新结果一起显示（状态是整体替换，不是叠加）。
        assertEquals("transfer-timeout", SourceSyncReceiveCopy.codeOf(state))
        assertFalse(SourceSyncReceiveCopy.lineOf(state)!!.contains("device-a"))
    }

    @Test
    fun successAfterFailureCarriesNoStaleCode() = runTest {
        val port = FakeReceivePort(SourceSyncTransferImportOutcome.TransferFailed("connect-failed"))
        val controller = SourceSyncReceiveController(port) { _, _ -> }
        controller.receive(SourceSyncReceiveForm(port = "8942"))
        assertEquals("connect-failed", SourceSyncReceiveCopy.codeOf(controller.state.value))

        port.outcome = importedOutcome(deviceId = "device-b")
        controller.receive(SourceSyncReceiveForm(port = "8943"))

        assertNull(SourceSyncReceiveCopy.codeOf(controller.state.value))
        assertTrue(controller.state.value is SourceSyncReceiveState.Received)
        assertEquals("device-b", (controller.state.value as SourceSyncReceiveState.Received).deviceId)
    }

    // ---------------------------------------------------------------- 单飞 / 取消 / 异常

    @Test
    fun duplicateTapSendsOnlyOneReceive() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeReceivePort(importedOutcome(), gate = gate)
        var loads = 0
        val controller = SourceSyncReceiveController(port) { _, _ -> loads++ }

        val first = launch { controller.receive(SourceSyncReceiveForm(port = "8942")) }
        runCurrent()
        assertEquals(SourceSyncReceiveState.Receiving, controller.state.value)

        assertFalse(controller.receive(SourceSyncReceiveForm(port = "8942")))
        assertEquals(1, port.calls)

        gate.complete(Unit)
        first.join()
        assertEquals(1, loads)
        assertTrue(controller.state.value is SourceSyncReceiveState.Received)
    }

    @Test
    fun receivingStateIsClearedAfterTheResult() = runTest {
        val controller = SourceSyncReceiveController(FakeReceivePort(importedOutcome())) { _, _ -> }

        controller.receive(SourceSyncReceiveForm(port = "8942"))

        assertTrue(controller.state.value != SourceSyncReceiveState.Receiving)
        // 进行中已经结束，因此可以再发一次。
        assertTrue(controller.receive(SourceSyncReceiveForm(port = "8943")))
    }

    @Test
    fun cancelledReceiveGoesBackToIdle() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakeReceivePort(importedOutcome(), gate = gate)
        var loads = 0
        val controller = SourceSyncReceiveController(port) { _, _ -> loads++ }

        val job = launch { controller.receive(SourceSyncReceiveForm(port = "8942")) }
        runCurrent()
        assertEquals(SourceSyncReceiveState.Receiving, controller.state.value)

        job.cancel()
        runCurrent()

        assertEquals(SourceSyncReceiveState.Idle, controller.state.value)
        assertEquals(0, loads)
        // 取消路径也不能把闸门留在"已占用"，否则按钮永远点不动（端口不再挂闸门才能跑完）。
        gate.complete(Unit)
        assertTrue(controller.receive(SourceSyncReceiveForm(port = "8943")))
    }

    @Test
    fun portExceptionBecomesALocalCodeAndWritesNothing() = runTest {
        val seeded = seededSession()
        val before = seeded.session.state.value
        val controller = SourceSyncReceiveController(SourceSyncReceivePort { _, _, _ -> error("boom") }) { json, name ->
            seeded.session.loadEnvelope(json, name)
        }

        assertTrue(controller.receive(SourceSyncReceiveForm(port = "8942")))

        val state = controller.state.value
        assertEquals(SourceSyncReceiveController.RECEIVE_FAILED, SourceSyncReceiveCopy.codeOf(state))
        assertEquals("IllegalStateException", (state as SourceSyncReceiveState.Failed).detail)
        assertEquals(before, seeded.session.state.value)
    }

    @Test
    fun clearDropsThePreviousResult() = runTest {
        val controller = SourceSyncReceiveController(FakeReceivePort(importedOutcome())) { _, _ -> }
        controller.receive(SourceSyncReceiveForm(port = "8942"))

        controller.clear()

        assertEquals(SourceSyncReceiveState.Idle, controller.state.value)
        assertNull(SourceSyncReceiveCopy.lineOf(controller.state.value))
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun resultLineShowsTheCodeAndTheDiagnosticVerbatim() {
        assertEquals(
            "接收失败：not-loopback",
            SourceSyncReceiveCopy.lineOf(SourceSyncReceiveState.Failed("not-loopback", null)),
        )
        assertEquals(
            "接收失败：connect-failed（Connection refused）",
            SourceSyncReceiveCopy.lineOf(SourceSyncReceiveState.Failed("connect-failed", "Connection refused")),
        )
        assertEquals(
            "信封被拒：malformed-json",
            SourceSyncReceiveCopy.lineOf(SourceSyncReceiveState.Rejected("malformed-json", null)),
        )
    }

    @Test
    fun receivedLineAsksForManualPreview() {
        val line = SourceSyncReceiveCopy.lineOf(SourceSyncReceiveState.Received("device-a", "x"))

        assertTrue(line!!.contains("device-a"))
        assertTrue(line.contains("预览"))
        val unknown = SourceSyncReceiveCopy.lineOf(SourceSyncReceiveState.Received(null, "x"))
        assertTrue(unknown!!.startsWith("已收下信封（设备未知）"))
    }

    // ---------------------------------------------------------------- 夹具

    /** 已经"选过文件 + 预览 + 允许一条"的会话：失败路径必须让它一个字都不变。 */
    private suspend fun seededSession(): SeededSession {
        val preview = FakePreviewPort(ready(plan()))
        val apply = FakeApplyPort()
        val session = SourceSyncImportSession(preview, apply)
        session.loadEnvelope(envelopeJson(), "manual-envelope.json")
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)
        return SeededSession(session, preview, apply)
    }

    private class SeededSession(
        val session: SourceSyncImportSession,
        val preview: FakePreviewPort,
        val apply: FakeApplyPort,
    )

    private class FakeReceivePort(
        var outcome: SourceSyncTransferImportOutcome,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : SourceSyncReceivePort {
        var calls = 0
        var host: String? = null
        var challenge: String? = null

        override suspend fun receive(
            host: String,
            port: Int,
            expectedChallenge: String?,
        ): SourceSyncTransferImportOutcome {
            calls++
            this.host = host
            this.challenge = expectedChallenge
            gate?.await()
            return outcome
        }
    }

    private class FakePreviewPort(private val result: SourceSyncPreview) : SourceSyncPreviewPort {
        var calls = 0

        override suspend fun preview(envelopeJson: String): SourceSyncPreview {
            calls++
            return result
        }
    }

    private class FakeApplyPort : SourceSyncApplyPort {
        var calls = 0

        override suspend fun apply(envelopeJson: String): SourceSyncImportResult {
            calls++
            return SourceSyncImportResult()
        }
    }

    private fun createCandidate(id: String) = SourceSyncCandidate(
        action = SourceSyncAction.CREATE,
        reason = SourceSyncReasons.NEW_SOURCE,
        syncSourceId = id,
        sourceType = "emby",
        isKnownSourceType = true,
        displayName = "客厅 Emby",
    )

    private fun unsupportedCandidate() = SourceSyncCandidate(
        action = SourceSyncAction.UNSUPPORTED,
        reason = SourceSyncReasons.UNKNOWN_SOURCE_TYPE,
        syncSourceId = "sync-quantum",
        sourceType = "quantum-nas",
        isKnownSourceType = false,
        displayName = "Quantum",
    )

    private fun plan() = SourceSyncPlan(listOf(createCandidate("sync-1"), unsupportedCandidate()))

    private fun ready(plan: SourceSyncPlan) = SourceSyncPreview.Ready(
        plan = plan,
        summary = SourceSyncSummary.fromPlan(plan),
        deviceId = "device-a",
        snapshotId = "snap-1",
    )

    private fun importedOutcome(
        json: String = envelopeJson(),
        deviceId: String = "device-a",
    ): SourceSyncTransferImportOutcome = SourceSyncTransferImportOutcome.Imported(
        SourceSyncImportResult(deviceId = deviceId, snapshotId = "snap-1"),
        json,
    )

    private fun envelopeJson(): String = SourceSyncCodec.encode(
        SourceSyncEnvelope(
            deviceId = "device-a",
            snapshotId = "snap-1",
            sources = listOf(
                com.mediahub.core.common.sync.SourceRecord(
                    syncSourceId = "sync-1",
                    sourceType = SyncSourceTypeValue.parse("emby"),
                    displayName = "客厅 Emby",
                ),
                com.mediahub.core.common.sync.SourceRecord(
                    syncSourceId = "sync-quantum",
                    sourceType = SyncSourceTypeValue.parse("quantum-nas"),
                    displayName = "Quantum",
                ),
            ),
        ),
    )
}
