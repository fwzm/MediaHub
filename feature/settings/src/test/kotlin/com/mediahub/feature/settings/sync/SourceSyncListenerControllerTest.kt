package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCancellation
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SourceSyncSummary
import com.mediahub.core.common.sync.SourceSyncTlsReasons
import com.mediahub.core.common.sync.SourceSyncTransferListener
import com.mediahub.core.common.sync.SourceSyncTransferOptions
import com.mediahub.core.common.sync.SourceSyncTransferReasons
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.core.database.sync.AppliedSourceChange
import com.mediahub.core.database.sync.SourceSyncApplyReport
import com.mediahub.core.database.sync.SourceSyncPreview
import com.mediahub.core.database.sync.SourceSyncReceiveEcho
import com.mediahub.core.database.sync.SourceSyncTransferImportOutcome
import com.mediahub.core.database.sync.SourceSyncWrittenSource
import com.mediahub.core.logging.LogBuffer
import com.mediahub.core.logging.MemoryLogger
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

/** 测试用指纹：与 core:common 的 tls 夹具（mediahub-sync-test.p12）同一枚，只当字符串用。 */
private val LISTEN_PIN: String = "2bb12d139178ee3b0f219e409a89c99b51be165914e21189eeb039b2e4dd37ce"

private fun imported(
    envelopeJson: String,
    deviceId: String? = "device-a",
): SourceSyncTransferImportOutcome.Imported = SourceSyncTransferImportOutcome.Imported(
    result = SourceSyncReceiveEcho(
        deviceId = deviceId,
        snapshotId = "snap-1",
        envelopeContentHash = "hash-1",
    ),
    envelopeJson = envelopeJson,
)

private fun transferred(
    reason: String,
    detail: String? = null,
): SourceSyncTransferImportOutcome = SourceSyncTransferImportOutcome.TransferFailed(reason, detail)

private fun rejectedEnvelope(
    reason: String,
    message: String? = null,
): SourceSyncTransferImportOutcome = SourceSyncTransferImportOutcome.EnvelopeRejected(reason, message)

private fun opened(session: SourceSyncListenerSession): SourceSyncListenerOpenResult =
    SourceSyncListenerOpenResult.Opened(session)

private fun openFailed(code: String, detail: String? = null): SourceSyncListenerOpenResult =
    SourceSyncListenerOpenResult.Failed(code, detail)

private fun createCandidate(id: String): SourceSyncCandidate = SourceSyncCandidate(
    action = SourceSyncAction.CREATE,
    reason = SourceSyncReasons.NEW_SOURCE,
    syncSourceId = id,
    sourceType = "emby",
    isKnownSourceType = true,
    displayName = "客厅 Emby",
)

private fun unsupportedCandidate(): SourceSyncCandidate = SourceSyncCandidate(
    action = SourceSyncAction.UNSUPPORTED,
    reason = SourceSyncReasons.UNKNOWN_SOURCE_TYPE,
    syncSourceId = "sync-quantum",
    sourceType = "quantum-nas",
    isKnownSourceType = false,
    displayName = "Quantum",
)

private fun planOf(): SourceSyncPlan =
    SourceSyncPlan(listOf(createCandidate("sync-1"), unsupportedCandidate()))

private fun readyPreview(): SourceSyncPreview.Ready = SourceSyncPreview.Ready(
    plan = planOf(),
    summary = SourceSyncSummary.fromPlan(planOf()),
    deviceId = "device-a",
    snapshotId = "snap-1",
)

private fun appliedResult(): SourceSyncApplyReport = SourceSyncApplyReport(
    peerDeviceId = "device-a",
    applied = 1,
    wrote = listOf(SourceSyncWrittenSource(syncSourceId = "sync-1", serverId = "srv-1")),
)

private fun envelopeText(): String = SourceSyncCodec.encode(
    SourceSyncEnvelope(
        deviceId = "device-a",
        snapshotId = "snap-1",
        sources = listOf(
            SourceRecord(
                syncSourceId = "sync-1",
                sourceType = SyncSourceTypeValue.parse("emby"),
                displayName = "客厅 Emby",
            ),
        ),
    ),
)

/** 监听工厂替身：记调用次数与参数，按构造时给的结果回答（不起 socket）。 */
private class FakeFactory(
    var availability: SourceSyncListenTlsAvailability = SourceSyncListenTlsAvailability(
        available = false,
        note = SourceSyncListenCopy.TLS_UNAVAILABLE,
    ),
    var opened: SourceSyncListenerOpenResult,
) : SourceSyncListenerFactory {

    var openCalls = 0

    var lastPort: Int? = null

    var lastTls: Boolean? = null

    var lastReceipt: Boolean? = null

    var lastHost: String? = null

    var lastAllowExplicitHost: Boolean? = null

    var lastAuth: SourceSyncTransferOptions? = null

    var authCalls = 0

    var lastBudget: Int? = null

    override fun tlsAvailability(): SourceSyncListenTlsAvailability = availability

    override fun open(port: Int, tls: Boolean): SourceSyncListenerOpenResult = open(port, tls, false)

    override fun open(port: Int, tls: Boolean, receipt: Boolean): SourceSyncListenerOpenResult {
        openCalls++
        lastPort = port
        lastTls = tls
        lastReceipt = receipt
        return opened
    }

    // W6-52：显式地址与「允许非回环单播」开关原样落到这里，供用例断言界面层有没有把话传到。
    override fun open(
        port: Int,
        tls: Boolean,
        receipt: Boolean,
        host: String?,
        allowExplicitHost: Boolean,
    ): SourceSyncListenerOpenResult {
        lastHost = host
        lastAllowExplicitHost = allowExplicitHost
        return open(port, tls, receipt)
    }

    // W6-53：对端口令要求原样落到这里；null 表示不要求认证。
    override fun open(
        port: Int,
        tls: Boolean,
        receipt: Boolean,
        host: String?,
        allowExplicitHost: Boolean,
        auth: SourceSyncTransferOptions?,
    ): SourceSyncListenerOpenResult {
        authCalls++
        lastAuth = auth
        return open(port, tls, receipt, host, allowExplicitHost)
    }

    // W6-54：等待预算（毫秒）原样落到这里，供用例断言界面层有没有把数字传到。
    override fun open(
        port: Int,
        tls: Boolean,
        receipt: Boolean,
        host: String?,
        allowExplicitHost: Boolean,
        auth: SourceSyncTransferOptions?,
        budgetMillis: Int,
    ): SourceSyncListenerOpenResult {
        lastBudget = budgetMillis
        return open(port, tls, receipt, host, allowExplicitHost, auth)
    }
}

/** 监听会话替身：可挂一道闸（用来测并发重入与取消），也可换下一次的结果。 */
private class FakeSession(
    override val port: Int = 45_678,
    override val tlsEnabled: Boolean = false,
    override val certificateFingerprint: String? = null,
    override val address: String = SourceSyncTransferListener.DEFAULT_HOST,
    var outcome: SourceSyncTransferImportOutcome = imported("{}"),
) : SourceSyncListenerSession {

    var receiveCalls = 0

    var closeCalls = 0

    var gate: CompletableDeferred<Unit>? = null

    /** W6-61：控制器传给会话的取消把手（断言「界面层有没有把话传到」）。 */
    var lastCancel: SourceSyncCancellation? = null

    override suspend fun receiveOnce(): SourceSyncTransferImportOutcome = receiveOnce(null)

    override suspend fun receiveOnce(cancel: SourceSyncCancellation?): SourceSyncTransferImportOutcome {
        receiveCalls++
        lastCancel = cancel
        gate?.await()
        return outcome
    }

    override fun close() {
        closeCalls++
    }
}

private class FakePreviewPort(private val result: SourceSyncPreview) : SourceSyncPreviewPort {

    var calls = 0

    override suspend fun preview(envelopeJson: String): SourceSyncPreview {
        calls++
        return result
    }
}

private class FakeApplyPort(private val result: SourceSyncApplyReport) : SourceSyncApplyPort {

    var calls = 0
    var allowedKeys: Set<String> = emptySet()
    var skippedKeys: Set<String> = emptySet()

    override suspend fun apply(
        envelopeJson: String,
        allowedKeys: Set<String>,
        skippedKeys: Set<String>,
    ): SourceSyncApplyReport {
        calls++
        this.allowedKeys = allowedKeys
        this.skippedKeys = skippedKeys
        return result
    }
}

/**
 * 「接收（监听）」状态机（W6-36，纯 JVM 证据）。
 *
 * 全部用假的监听工厂与会话替身：真 socket 路径（accept、TLS 握手、解帧、认证）由
 * core:common 的 SourceSyncTransferListenerTest 与 core:database 的 SourceSyncListenerImportTest 钉住。
 * 这里只断言界面层会做什么，最要紧的一条与 W6-27 相同：失败路径一个字都不写。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSyncListenerControllerTest {

    @Test
    fun defaultsAreIdleAndSayNothing() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertEquals(SourceSyncListenState.Idle, controller.state.value)
        assertFalse(controller.isListening)
        assertNull(SourceSyncListenCopy.lineOf(controller.state.value))
        assertNull(SourceSyncListenCopy.codeOf(controller.state.value))
        assertNull(SourceSyncListenCopy.portLineOf(controller.state.value))
        assertEquals(0, factory.openCalls)
    }

    @Test
    fun blankPortAsksTheOsForOne() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession(port = 45_678)))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm()))

        assertEquals(1, factory.openCalls)
        assertEquals(SourceSyncListenForm.SYSTEM_ASSIGNED_PORT, factory.lastPort)
        assertEquals(SourceSyncListenState.Listening(45_678, false, null), controller.state.value)
        assertTrue(controller.isListening)
    }

    @Test
    fun typedPortIsUsedVerbatim() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession(port = 8_899)))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm(port = "8899")))

        assertEquals(8_899, factory.lastPort)
        assertEquals(SourceSyncListenState.Listening(8_899, false, null), controller.state.value)
    }

    @Test
    fun invalidPortsNeverReachTheFactory() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        for (text in listOf("0", "65536", "-1", "abc", "9000.5", " 0x10")) {
            assertFalse("端口 " + text + " 不该开出监听", controller.start(SourceSyncListenForm(port = text)))
            assertEquals(
                SourceSyncListenerController.PORT_INVALID,
                SourceSyncListenCopy.codeOf(controller.state.value),
            )
            assertEquals(0, factory.openCalls)
            assertFalse(controller.isListening)
        }
        assertTrue(
            SourceSyncListenCopy.lineOf(controller.state.value).orEmpty()
                .contains(SourceSyncListenerController.PORT_INVALID),
        )
    }

    @Test
    fun listeningReportsTheBoundPortTlsFlagAndFingerprint() = runTest {
        val session = FakeSession(port = 9_001, tlsEnabled = true, certificateFingerprint = LISTEN_PIN)
        val factory = FakeFactory(
            availability = SourceSyncListenTlsAvailability(true, SourceSyncListenCopy.TLS_AVAILABLE_HINT),
            opened = opened(session),
        )
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm(port = "9001", tls = true)))

        assertTrue(factory.lastTls == true)
        assertEquals(SourceSyncListenState.Listening(9_001, true, LISTEN_PIN), controller.state.value)
        assertEquals(
            "监听 127.0.0.1:9001（TLS 1.3），证书指纹：" + LISTEN_PIN,
            SourceSyncListenCopy.portLineOf(controller.state.value),
        )
        assertNull(SourceSyncListenCopy.codeOf(controller.state.value))
    }

    @Test
    fun plaintextPortLineSaysPlaintext() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession(port = 9_002)))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        controller.start(SourceSyncListenForm())

        assertEquals(
            "监听 127.0.0.1:9002（明文）",
            SourceSyncListenCopy.portLineOf(controller.state.value),
        )
    }

    @Test
    fun receivingOnceDeliversTheEnvelopeIntoTheSession() = runTest {
        val preview = FakePreviewPort(readyPreview())
        val apply = FakeApplyPort(appliedResult())
        val session = SourceSyncImportSession(preview, apply)
        val envelope = envelopeText()
        val listener = FakeSession(outcome = imported(envelope))
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { json, name ->
            session.loadEnvelope(json, name)
        }

        assertTrue(controller.start(SourceSyncListenForm()))
        assertTrue(controller.awaitOnce())

        val importState = session.state.value
        assertEquals(SourceSyncImportStatus.READY_TO_PREVIEW, importState.status)
        assertEquals(envelope, importState.envelopeJson)
        assertNull(importState.preview)
        assertNull(importState.applied)
        assertEquals(0, preview.calls)
        assertEquals(0, apply.calls)
        assertEquals(1, listener.receiveCalls)
        assertEquals(
            SourceSyncListenState.Received(45_678, "device-a", SourceSyncListenerController.LISTEN_FILE_NAME),
            controller.state.value,
        )
    }

    @Test
    fun importedEnvelopeCarriesTheListenFileName() = runTest {
        val delivered = mutableListOf<Pair<String, String>>()
        val envelope = envelopeText()
        val controller = SourceSyncListenerController(
            FakeFactory(opened = opened(FakeSession(outcome = imported(envelope)))),
        ) { json, name -> delivered.add(json to name) }

        controller.start(SourceSyncListenForm())
        controller.awaitOnce()

        assertEquals(1, delivered.size)
        assertEquals(envelope, delivered[0].first)
        assertEquals(SourceSyncListenerController.LISTEN_FILE_NAME, delivered[0].second)
    }    @Test
    fun transferFailureKeepsTheSessionUntouched() = runTest {
        val seeded = seededSession()
        val before = seeded.session.state.value
        val listener = FakeSession(
            outcome = transferred(SourceSyncTransferReasons.TRANSFER_TIMEOUT, "对端在超时前没有响应"),
        )
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { json, name ->
            seeded.session.loadEnvelope(json, name)
        }

        assertTrue(controller.start(SourceSyncListenForm()))
        assertTrue(controller.awaitOnce())

        assertEquals(
            SourceSyncListenState.Failed(45_678, SourceSyncTransferReasons.TRANSFER_TIMEOUT, "对端在超时前没有响应"),
            controller.state.value,
        )
        assertEquals(before, seeded.session.state.value)
        assertEquals(1, seeded.preview.calls)
        assertEquals(0, seeded.apply.calls)
        assertTrue(controller.isListening)
    }

    @Test
    fun rejectedEnvelopeKeepsTheSessionUntouched() = runTest {
        val seeded = seededSession()
        val before = seeded.session.state.value
        val listener = FakeSession(outcome = rejectedEnvelope("malformed-json", "文档不是 JSON。"))
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { json, name ->
            seeded.session.loadEnvelope(json, name)
        }

        controller.start(SourceSyncListenForm())
        assertTrue(controller.awaitOnce())

        assertEquals(
            SourceSyncListenState.Rejected(45_678, "malformed-json", "文档不是 JSON。"),
            controller.state.value,
        )
        assertEquals("malformed-json", SourceSyncListenCopy.codeOf(controller.state.value))
        assertEquals(before, seeded.session.state.value)
        assertEquals(0, seeded.apply.calls)
    }

    @Test
    fun rejectedImportResultWritesNothing() = runTest {
        val seeded = seededSession()
        val before = seeded.session.state.value
        val outcome = SourceSyncTransferImportOutcome.Imported(
            result = SourceSyncReceiveEcho(rejectedReason = "duplicate-sync-source-id"),
            envelopeJson = envelopeText(),
        )
        val controller = SourceSyncListenerController(
            FakeFactory(opened = opened(FakeSession(outcome = outcome))),
        ) { json, name -> seeded.session.loadEnvelope(json, name) }

        controller.start(SourceSyncListenForm())
        assertTrue(controller.awaitOnce())

        assertEquals(
            SourceSyncListenState.Rejected(45_678, "duplicate-sync-source-id", null),
            controller.state.value,
        )
        assertEquals(before, seeded.session.state.value)
        assertEquals(0, seeded.apply.calls)
    }

    @Test
    fun importedWithoutEnvelopeJsonIsALocalFailure() = runTest {
        val delivered = mutableListOf<Pair<String, String>>()
        val outcome = SourceSyncTransferImportOutcome.Imported(
            result = SourceSyncReceiveEcho(deviceId = "device-a", snapshotId = "snap-1"),
            envelopeJson = null,
        )
        val controller = SourceSyncListenerController(
            FakeFactory(opened = opened(FakeSession(outcome = outcome))),
        ) { json, name -> delivered.add(json to name) }

        controller.start(SourceSyncListenForm())
        assertTrue(controller.awaitOnce())

        assertEquals(
            SourceSyncListenState.Failed(
                45_678,
                SourceSyncListenerController.RECEIVE_FAILED,
                SourceSyncListenerController.MISSING_ENVELOPE,
            ),
            controller.state.value,
        )
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun tlsWithoutAnIdentityIsRefusedBeforeOpening() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession(tlsEnabled = true)))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertFalse(controller.start(SourceSyncListenForm(tls = true)))

        assertEquals(0, factory.openCalls)
        assertFalse(controller.isListening)
        assertEquals(
            SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE,
            SourceSyncListenCopy.codeOf(controller.state.value),
        )
        assertTrue(
            SourceSyncListenCopy.lineOf(controller.state.value).orEmpty()
                .contains(SourceSyncListenCopy.TLS_UNAVAILABLE),
        )
    }

    @Test
    fun tlsWithAnAvailableIdentityOpensAPinnedListener() = runTest {
        val session = FakeSession(port = 9_100, tlsEnabled = true, certificateFingerprint = LISTEN_PIN)
        val factory = FakeFactory(
            availability = SourceSyncListenTlsAvailability(true, SourceSyncListenCopy.TLS_AVAILABLE_HINT),
            opened = opened(session),
        )
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm(tls = true)))

        assertEquals(1, factory.openCalls)
        assertTrue(factory.lastTls == true)
        assertEquals(SourceSyncListenState.Listening(9_100, true, LISTEN_PIN), controller.state.value)
    }

    @Test
    fun listenerOpenFailureIsReportedAsItsCode() = runTest {
        val factory = FakeFactory(
            opened = openFailed(SourceSyncTransferReasons.LISTENER_FAILED, "java.net.BindException"),
        )
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertFalse(controller.start(SourceSyncListenForm(port = "8899")))

        assertEquals(8_899, factory.lastPort)
        assertFalse(controller.isListening)
        assertEquals(
            SourceSyncListenState.Failed(
                null,
                SourceSyncTransferReasons.LISTENER_FAILED,
                "java.net.BindException",
            ),
            controller.state.value,
        )
        assertTrue(
            SourceSyncListenCopy.lineOf(controller.state.value).orEmpty()
                .contains(SourceSyncTransferReasons.LISTENER_FAILED),
        )
    }

    @Test
    fun stopIsIdempotentAndClosesOnce() = runTest {
        val session = FakeSession()
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(session))) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm()))
        controller.stop()
        controller.stop()

        assertEquals(1, session.closeCalls)
        assertFalse(controller.isListening)
        assertEquals(SourceSyncListenState.Idle, controller.state.value)
    }

    @Test
    fun awaitOnceWithoutListeningDoesNothing() = runTest {
        val session = FakeSession()
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(session))) { _, _ -> }

        assertFalse(controller.awaitOnce())

        assertEquals(0, session.receiveCalls)
        assertEquals(SourceSyncListenState.Idle, controller.state.value)
        assertNull(SourceSyncListenCopy.lineOf(controller.state.value))
    }

    @Test
    fun duplicateReceiveIsRejectedWhileOneIsInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val listener = FakeSession(outcome = imported(envelopeText()))
        listener.gate = gate
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }

        controller.start(SourceSyncListenForm(port = "8899"))
        val first = launch { controller.awaitOnce() }
        runCurrent()

        assertEquals(SourceSyncListenState.Receiving(45_678), controller.state.value)
        assertEquals(1, listener.receiveCalls)
        assertFalse("同一时刻只允许一次接收", controller.awaitOnce())
        assertEquals(1, listener.receiveCalls)

        gate.complete(Unit)
        first.join()
        assertTrue(controller.state.value is SourceSyncListenState.Received)
    }

    @Test
    fun startWhileListeningIsRejected() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession(port = 9_200)))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm()))
        assertFalse("已经在监听时不允许再开一个", controller.start(SourceSyncListenForm(port = "9201")))

        assertEquals(1, factory.openCalls)
        assertEquals(SourceSyncListenState.Listening(9_200, false, null), controller.state.value)
    }

    @Test
    fun acceptTimeoutIsReportedAsATimeoutCode() = runTest {
        val listener = FakeSession(outcome = transferred(SourceSyncTransferReasons.TRANSFER_TIMEOUT))
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }

        controller.start(SourceSyncListenForm())
        assertTrue(controller.awaitOnce())

        assertEquals(
            SourceSyncTransferReasons.TRANSFER_TIMEOUT,
            SourceSyncListenCopy.codeOf(controller.state.value),
        )
        assertTrue(controller.isListening)
    }

    @Test
    fun twoReceivesInARowBothReachThePreviewPath() = runTest {
        val preview = FakePreviewPort(readyPreview())
        val apply = FakeApplyPort(appliedResult())
        val session = SourceSyncImportSession(preview, apply)
        val first = envelopeText()
        val second = first.replace("device-a", "device-b")
        val listener = FakeSession(outcome = imported(first))
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { json, name ->
            session.loadEnvelope(json, name)
        }

        controller.start(SourceSyncListenForm())
        assertTrue(controller.awaitOnce())
        assertEquals(first, session.state.value.envelopeJson)

        listener.outcome = imported(second)
        assertTrue(controller.awaitOnce())

        assertEquals(2, listener.receiveCalls)
        assertEquals(second, session.state.value.envelopeJson)
        assertEquals(SourceSyncImportStatus.READY_TO_PREVIEW, session.state.value.status)
        assertEquals(0, preview.calls)
        assertEquals(0, apply.calls)
        assertEquals(
            SourceSyncListenState.Received(45_678, "device-a", SourceSyncListenerController.LISTEN_FILE_NAME),
            controller.state.value,
        )
    }

    @Test
    fun successAfterFailureCarriesNoStaleCode() = runTest {
        val listener = FakeSession(outcome = transferred(SourceSyncTransferReasons.IO_FAILED))
        val delivered = mutableListOf<String>()
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { json, _ ->
            delivered.add(json)
        }

        controller.start(SourceSyncListenForm())
        controller.awaitOnce()
        assertEquals(SourceSyncTransferReasons.IO_FAILED, SourceSyncListenCopy.codeOf(controller.state.value))

        listener.outcome = imported(envelopeText())
        assertTrue(controller.awaitOnce())

        assertNull("成功之后不许留下上一次的失败码", SourceSyncListenCopy.codeOf(controller.state.value))
        assertEquals(1, delivered.size)
        assertTrue(
            SourceSyncListenCopy.lineOf(controller.state.value).orEmpty().contains("已收下信封"),
        )
    }

    @Test
    fun cancelledReceiveGoesBackToListening() = runTest {
        val gate = CompletableDeferred<Unit>()
        val listener = FakeSession()
        listener.gate = gate
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }

        controller.start(SourceSyncListenForm())
        val job = launch { controller.awaitOnce() }
        runCurrent()
        assertTrue(controller.state.value is SourceSyncListenState.Receiving)

        job.cancel()
        job.join()
        gate.complete(Unit)

        assertEquals(SourceSyncListenState.Listening(45_678, false, null), controller.state.value)
        assertTrue(controller.isListening)
    }

    @Test
    fun aCancelledWaitGoesBackToListeningWithoutAnyCode() = runTest {
        // W6-61：取消走的是把手（不是 close、也不是协程取消），界面语义与 W6-58 的接收端一致。
        val gate = CompletableDeferred<Unit>()
        val listener = FakeSession()
        listener.gate = gate
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }
        val cancellation = SourceSyncCancellation()

        controller.start(SourceSyncListenForm())
        val job = launch { controller.awaitOnce(cancellation) }
        runCurrent()
        assertEquals(45_678, (controller.state.value as SourceSyncListenState.Receiving).port)

        // 把手置位就当成「这次被取消」：会话给出本地码，控制器把它折成「正在监听」。
        cancellation.cancel()
        listener.outcome = transferred(com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED)
        gate.complete(Unit)
        job.join()

        assertEquals(
            "取消不是失败：状态必须回到正在监听（W6-86 起带取消账）",
            SourceSyncListenState.Listening(
                45_678,
                false,
                null,
                cancelledReceives = 1,
                lastCancelledReason = com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED,
            ),
            controller.state.value,
        )
        assertNull("取消不许留下任何稳定码", SourceSyncListenCopy.codeOf(controller.state.value))
        assertTrue("监听仍然开着，可以立刻再等一次", controller.isListening)
        assertEquals("把手必须被送到会话那一层", cancellation, listener.lastCancel)
        assertEquals(0, listener.closeCalls)
    }

    @Test
    fun aCancelledWaitCanBeFollowedByAnotherOneThatSucceeds() = runTest {
        val listener = FakeSession()
        val delivered = mutableListOf<String>()
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { json, _ ->
            delivered.add(json)
        }
        val gate = CompletableDeferred<Unit>()
        listener.gate = gate
        val cancellation = SourceSyncCancellation()

        controller.start(SourceSyncListenForm())
        val first = launch { controller.awaitOnce(cancellation) }
        runCurrent()
        cancellation.cancel()
        listener.outcome = transferred(com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED)
        gate.complete(Unit)
        first.join()
        assertEquals(
            SourceSyncListenState.Listening(
                45_678,
                false,
                null,
                cancelledReceives = 1,
                lastCancelledReason = com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED,
            ),
            controller.state.value,
        )

        // 第二次：换一枚新把手，正常收下一封信封 —— 取消没有把监听弄坏。
        listener.gate = null
        listener.outcome = imported(envelopeText())
        assertTrue(controller.awaitOnce(SourceSyncCancellation()))

        assertTrue(controller.state.value is SourceSyncListenState.Received)
        assertEquals(1, delivered.size)
        assertEquals(2, listener.receiveCalls)
        assertEquals(0, listener.closeCalls)
    }

    @Test
    fun aRealFailureAfterACancelStillReportsItsCode() = runTest {
        // 取消优先只覆盖「这次被取消」；取消之后真出的错仍要如实显示（不能把一切都当取消吞掉）。
        val listener = FakeSession(outcome = transferred(SourceSyncTransferReasons.IO_FAILED))
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }

        controller.start(SourceSyncListenForm())
        assertTrue(controller.awaitOnce(SourceSyncCancellation()))

        assertEquals(SourceSyncTransferReasons.IO_FAILED, SourceSyncListenCopy.codeOf(controller.state.value))
        assertTrue(controller.state.value is SourceSyncListenState.Failed)
        assertTrue("失败不该把监听关掉", controller.isListening)
    }


    @Test
    fun aCancelledWaitLeavesAnAccountInsteadOfSilentlyResetting() = runTest {
        // W6-86：取消不是失败、也不留稳定码，但必须留账——只把状态跳回「正在监听」时，
        // 用户分不清是「我取消了」还是「点了没反应」。
        val gate = CompletableDeferred<Unit>()
        val listener = FakeSession()
        listener.gate = gate
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }
        val cancellation = SourceSyncCancellation()

        controller.start(SourceSyncListenForm())
        val job = launch { controller.awaitOnce(cancellation) }
        runCurrent()
        cancellation.cancel()
        listener.outcome = transferred(com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED)
        gate.complete(Unit)
        job.join()

        val listening = controller.state.value as SourceSyncListenState.Listening
        assertEquals("取消了一次就要记一次", 1, listening.cancelledReceives)
        assertEquals("最近一次的码要留下来", com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED, listening.lastCancelledReason)
        assertNull("取消仍然不许变成失败码", SourceSyncListenCopy.codeOf(listening))
        assertEquals(
            "已取消 1 次等待（最近一次原因：transfer-cancelled）；取消不是失败，监听仍然开着，可以接着等一次发送。",
            SourceSyncListenCopy.cancelledLineOf(listening),
        )
    }

    @Test
    fun twoCancelledWaitsAddUpAndTheLastReasonWins() = runTest {
        val listener = FakeSession()
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }
        controller.start(SourceSyncListenForm())

        listener.gate = CompletableDeferred<Unit>()
        val first = SourceSyncCancellation()
        val firstJob = launch { controller.awaitOnce(first) }
        runCurrent()
        first.cancel()
        listener.outcome = transferred(com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED)
        listener.gate!!.complete(Unit)
        firstJob.join()

        listener.gate = CompletableDeferred<Unit>()
        val second = SourceSyncCancellation()
        val secondJob = launch { controller.awaitOnce(second) }
        runCurrent()
        second.cancel()
        // 第二次取消时对端恰好已经断了：原因是别的东西，控制器也必须照抄，不许一律写成本地码。
        listener.outcome = transferred(SourceSyncTransferReasons.IO_FAILED)
        listener.gate!!.complete(Unit)
        secondJob.join()

        val listening = controller.state.value as SourceSyncListenState.Listening
        assertEquals(2, listening.cancelledReceives)
        assertEquals(SourceSyncTransferReasons.IO_FAILED, listening.lastCancelledReason)
        assertTrue(
            SourceSyncListenCopy.cancelledLineOf(listening).orEmpty().contains("已取消 2 次等待"),
        )
    }

    @Test
    fun aCancelledWaitThatNeverProducedAFailureFallsBackToTheLocalCode() = runTest {
        // 把手置位但会话恰好成功返回：仍然算「这次被取消」，码退回本地码而不是留空。
        val gate = CompletableDeferred<Unit>()
        val listener = FakeSession()
        listener.gate = gate
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }
        val cancellation = SourceSyncCancellation()

        controller.start(SourceSyncListenForm())
        val job = launch { controller.awaitOnce(cancellation) }
        runCurrent()
        cancellation.cancel()
        listener.outcome = imported(envelopeText())
        gate.complete(Unit)
        job.join()

        val listening = controller.state.value as SourceSyncListenState.Listening
        assertEquals(1, listening.cancelledReceives)
        assertEquals(com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED, listening.lastCancelledReason)
        assertEquals("成功返回也不许被当成收到", 0, listener.closeCalls)
    }

    @Test
    fun stoppingTheListenerClearsTheCancelAccount() = runTest {
        val gate = CompletableDeferred<Unit>()
        val first = FakeSession()
        first.gate = gate
        val second = FakeSession()
        val factory = FakeFactory(opened = opened(first))
        val controller = SourceSyncListenerController(factory) { _, _ -> }
        val cancellation = SourceSyncCancellation()

        controller.start(SourceSyncListenForm())
        val job = launch { controller.awaitOnce(cancellation) }
        runCurrent()
        cancellation.cancel()
        first.outcome = transferred(com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED)
        gate.complete(Unit)
        job.join()
        assertEquals(1, (controller.state.value as SourceSyncListenState.Listening).cancelledReceives)

        controller.stop()
        assertNull("关掉监听时这一行必须消失", SourceSyncListenCopy.cancelledLineOf(controller.state.value))

        // 再开一场：旧账不能挂到新端口上。
        factory.opened = opened(second)
        assertTrue(controller.start(SourceSyncListenForm()))
        val fresh = controller.state.value as SourceSyncListenState.Listening
        assertEquals(0, fresh.cancelledReceives)
        assertNull(fresh.lastCancelledReason)
        assertNull(SourceSyncListenCopy.cancelledLineOf(fresh))
    }

    @Test
    fun clearResultDropsTheReceiptButKeepsListening() = runTest {
        val listener = FakeSession(outcome = imported(envelopeText()))
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }

        controller.start(SourceSyncListenForm())
        controller.awaitOnce()
        assertTrue(controller.state.value is SourceSyncListenState.Received)

        controller.clearResult()

        assertEquals(SourceSyncListenState.Listening(45_678, false, null), controller.state.value)
        assertTrue(controller.isListening)
        assertEquals(45_678, controller.state.value.let { (it as SourceSyncListenState.Listening).port })
    }

    @Test
    fun resultLineShowsTheCodeAndTheDiagnosticVerbatim() = runTest {
        assertEquals(
            "失败：listener-failed",
            SourceSyncListenCopy.lineOf(SourceSyncListenState.Failed(null, "listener-failed", null)),
        )
        assertEquals(
            "失败：io-failed（读取时连接被重置）",
            SourceSyncListenCopy.lineOf(SourceSyncListenState.Failed(45_678, "io-failed", "读取时连接被重置")),
        )
        assertEquals(
            "信封被拒：malformed-json（文档不是 JSON。）",
            SourceSyncListenCopy.lineOf(
                SourceSyncListenState.Rejected(45_678, "malformed-json", "文档不是 JSON。"),
            ),
        )
        assertTrue(
            SourceSyncListenCopy.lineOf(SourceSyncListenState.Received(45_678, "device-a", "x.json"))
                .orEmpty().contains(SourceSyncListenCopy.RECEIVED_HINT),
        )
    }

    // ------- W6-40 回执开关（界面先不加控件，默认关） -------

    @Test
    fun formReceiptFlagIsForwardedToTheFactory() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm()))
        assertEquals("默认必须是关着的", false, factory.lastReceipt)

        controller.stop()
        assertTrue(controller.start(SourceSyncListenForm(port = "", tls = false, receipt = true)))
        assertEquals("开了就必须原样传下去", true, factory.lastReceipt)
        assertEquals(2, factory.openCalls)
    }

    @Test
    fun receivedCarriesTheReceiptFlagIntoTheState() = runTest {
        val listener = FakeSession(
            outcome = SourceSyncTransferImportOutcome.Imported(
                result = SourceSyncReceiveEcho(
                    deviceId = "device-a",
                    snapshotId = "snap-1",
                    envelopeContentHash = "hash-1",
                ),
                envelopeJson = envelopeText(),
                receiptWritten = true,
            ),
        )
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }

        controller.start(SourceSyncListenForm(receipt = true))
        assertTrue(controller.awaitOnce())

        val state = controller.state.value
        assertTrue("期待已收到状态，实际 " + state, state is SourceSyncListenState.Received)
        assertTrue((state as SourceSyncListenState.Received).receiptWritten)
        assertTrue(
            "回执成功时结果行要点出对端能确认",
            SourceSyncListenCopy.lineOf(state).orEmpty().contains(SourceSyncListenCopy.RECEIPT_WRITTEN_HINT),
        )
    }

    @Test
    fun defaultFormAsksForNoReceiptAndSaysNothingAboutIt() = runTest {
        val listener = FakeSession(outcome = imported(envelopeText()))
        val controller = SourceSyncListenerController(FakeFactory(opened = opened(listener))) { _, _ -> }

        controller.start(SourceSyncListenForm())
        assertTrue(controller.awaitOnce())

        val state = controller.state.value as SourceSyncListenState.Received
        assertFalse("默认关着就不该声称回了回执", state.receiptWritten)
        assertFalse(
            SourceSyncListenCopy.lineOf(state).orEmpty().contains(SourceSyncListenCopy.RECEIPT_WRITTEN_HINT),
        )
    }
    // ------- W6-52 绑定地址与「允许非回环单播」开关 -------

    @Test
    fun defaultFormLeavesTheHostBlankAndKeepsTheSwitchOff() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm()))

        // 表单的 host 缺省是空串而不是 null：空白由 core 的 SourceSyncHostAddress 当「缺省回环」处理，
        // 界面层不替用户猜一个地址。生产工厂把 null 与空串交给同一处解析，两种写法等价。
        assertEquals("", factory.lastHost)
        assertEquals("开关默认必须是关着的", false, factory.lastAllowExplicitHost)
    }

    @Test
    fun aTypedHostWithoutTheSwitchStillTravelsButStaysOff() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm(host = "192.168.1.5")))

        assertEquals("写了什么就传什么，判断在 core", "192.168.1.5", factory.lastHost)
        assertEquals("没点开关就不许放宽", false, factory.lastAllowExplicitHost)
    }

    @Test
    fun hostAndSwitchTravelVerbatimAndTrimmingIsLeftToTheCore() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession(address = "192.168.1.5")))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(
            controller.start(SourceSyncListenForm(host = "  192.168.1.5  ", allowExplicitHost = true)),
        )

        assertEquals("界面层不做 trim：空白由 core 的地址解析器收尾", "  192.168.1.5  ", factory.lastHost)
        assertEquals(true, factory.lastAllowExplicitHost)
    }

    @Test
    fun notLoopbackFromTheFactoryKeepsTheCodeAndTheSentenceVerbatim() = runTest {
        val sentence = "只允许绑定回环地址（127.0.0.1 / ::1 / localhost），实际 0.0.0.0"
        val factory = FakeFactory(opened = openFailed(SourceSyncTransferReasons.NOT_LOOPBACK, sentence))
        val handled = mutableListOf<String>()
        val controller = SourceSyncListenerController(factory) { envelope, _ -> handled += envelope }

        assertFalse(controller.start(SourceSyncListenForm(host = "0.0.0.0", allowExplicitHost = true)))

        val state = controller.state.value
        assertTrue("期待失败状态，实际 " + state, state is SourceSyncListenState.Failed)
        state as SourceSyncListenState.Failed
        assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, state.code)
        assertEquals(sentence, state.detail)
        assertNull("还没开起来就不该有端口", state.port)
        assertFalse("地址被拒时不能声称在监听", controller.isListening)
        assertTrue("地址被拒时一个字都不往导入页送", handled.isEmpty())
        assertEquals(
            "失败：" + SourceSyncTransferReasons.NOT_LOOPBACK + "（" + sentence + "）",
            SourceSyncListenCopy.lineOf(state),
        )
    }

    @Test
    fun theListeningLinesNameTheBoundHostInsteadOfLoopback() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession(port = 45_678, address = "192.168.1.5")))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(
            controller.start(SourceSyncListenForm(host = "192.168.1.5", allowExplicitHost = true)),
        )

        val state = controller.state.value
        assertTrue("期待监听状态，实际 " + state, state is SourceSyncListenState.Listening)
        assertEquals("192.168.1.5", (state as SourceSyncListenState.Listening).host)
        assertEquals(
            "正在监听 192.168.1.5:45678（明文），等 Windows 发送…",
            SourceSyncListenCopy.lineOf(state),
        )
        assertEquals("监听 192.168.1.5:45678（明文）", SourceSyncListenCopy.portLineOf(state))
        assertFalse(
            "既然绑的是那台机器的地址，就不该再写 127.0.0.1",
            SourceSyncListenCopy.portLineOf(state).orEmpty().contains("127.0.0.1"),
        )
    }

    // ------- W6-53 对端口令（HMAC 认证）开关 -------

    @Test
    fun defaultFormAsksForNoSecretAndPassesNoAuthOptions() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm()))

        assertEquals("默认不要求认证：工厂收到的认证选项必须是 null", null, factory.lastAuth)
        assertEquals(1, factory.authCalls)
        assertEquals("默认表单可以不填口令就提交", true, SourceSyncListenForm().canSubmit)
    }

    @Test
    fun authOnWithoutASecretIsRefusedBeforeAnySocket() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }
        val form = SourceSyncListenForm(auth = true)

        assertEquals("开了认证却没口令时，界面本来就不该放行", false, form.canSubmit)
        assertFalse(controller.start(form))

        val state = controller.state.value
        assertTrue("期待失败状态，实际 " + state, state is SourceSyncListenState.Failed)
        state as SourceSyncListenState.Failed
        assertEquals(SourceSyncListenerController.SECRET_MISSING, state.code)
        assertEquals(SourceSyncListenCopy.SECRET_REQUIRED, state.detail)
        assertNull(state.port)
        assertEquals("口令缺失时一个 socket 都不该开", 0, factory.openCalls)
        assertFalse(controller.isListening)
        assertEquals("失败：listen-secret-missing（" + SourceSyncListenCopy.SECRET_REQUIRED + "）", SourceSyncListenCopy.lineOf(state))
    }

    @Test
    fun authOnWithASecretPassesOptionsCarryingTheSecretBytes() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm(auth = true, secret = "s3cret")))

        val auth = factory.lastAuth
        assertTrue("开了认证就该把选项交给工厂，实际 " + auth, auth != null)
        auth as SourceSyncTransferOptions
        assertEquals(true, auth.authenticate)
        assertEquals(true, auth.hasSecret)
        assertTrue("口令按 UTF-8 原样交给 core", auth.secret.contentEquals("s3cret".toByteArray(Charsets.UTF_8)))
        assertEquals("口令进了状态行就等于泄漏", false, SourceSyncListenCopy.lineOf(controller.state.value).orEmpty().contains("s3cret"))
    }

    @Test
    fun aTypedSecretWithTheSwitchOffIsIgnored() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm(secret = "s3cret")))

        assertEquals("开关没点亮时，写了口令也不该要求对端认证", null, factory.lastAuth)
    }

    @Test
    fun theSecretNeverAppearsInTheFormToString() {
        val form = SourceSyncListenForm(host = "192.168.1.5", auth = true, secret = "s3cret")

        val text = form.toString()

        assertEquals("口令原文不得出现在 toString 里", false, text.contains("s3cret"))
        assertTrue("要如实说明打了码，实际 " + text, text.contains("secret=<redacted>"))
        assertTrue("其余字段照旧可读", text.contains("host=192.168.1.5") && text.contains("auth=true"))
    }

    // ------- W6-54 等待预算（毫秒）旋钮 -------

    @Test
    fun defaultFormPassesTheFifteenSecondBudgetAndCanSubmit() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }
        val form = SourceSyncListenForm()

        assertEquals("留空就是 15000 毫秒（与 Windows transfer serve 同值）", 15_000, form.budgetMillis)
        assertTrue(form.canSubmit)
        assertTrue(controller.start(form))

        assertEquals(15_000, factory.lastBudget)
    }

    @Test
    fun aTypedBudgetTravelsVerbatimToTheFactory() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm(budget = "30000")))

        assertEquals(30_000, factory.lastBudget)
        assertEquals("前后空白不该改变数字", 30_000, SourceSyncListenForm(budget = "  30000  ").budgetMillis)
    }

    @Test
    fun theBudgetRangeEndsAreBothAccepted() = runTest {
        val low = FakeFactory(opened = opened(FakeSession()))
        assertTrue(SourceSyncListenerController(low) { _, _ -> }.start(SourceSyncListenForm(budget = "100")))
        assertEquals(100, low.lastBudget)

        val high = FakeFactory(opened = opened(FakeSession()))
        assertTrue(SourceSyncListenerController(high) { _, _ -> }.start(SourceSyncListenForm(budget = "600000")))
        assertEquals(600_000, high.lastBudget)
    }

    @Test
    fun anOutOfRangeBudgetIsRefusedBeforeAnySocket() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }
        val form = SourceSyncListenForm(budget = "99")

        assertEquals("越界的预算连按钮都不该放行", false, form.canSubmit)
        assertFalse(controller.start(form))

        val state = controller.state.value
        assertTrue("期待失败状态，实际 " + state, state is SourceSyncListenState.Failed)
        state as SourceSyncListenState.Failed
        assertEquals(SourceSyncListenerController.BUDGET_INVALID, state.code)
        assertEquals(SourceSyncListenCopy.BUDGET_INVALID_HINT, state.detail)
        assertNull(state.port)
        assertEquals("预算非法时一个 socket 都不该开", 0, factory.openCalls)
        assertFalse(controller.isListening)
        assertEquals(
            "失败：listen-budget-invalid（" + SourceSyncListenCopy.BUDGET_INVALID_HINT + "）",
            SourceSyncListenCopy.lineOf(state),
        )
    }

    @Test
    fun aNonNumericBudgetIsRefusedAndStillShowsInTheFormToString() = runTest {
        val factory = FakeFactory(opened = opened(FakeSession()))
        val controller = SourceSyncListenerController(factory) { _, _ -> }
        val form = SourceSyncListenForm(budget = "12s", secret = "s3cret")

        assertFalse(controller.start(form))

        val state = controller.state.value
        assertTrue("期待失败状态，实际 " + state, state is SourceSyncListenState.Failed)
        assertEquals(SourceSyncListenerController.BUDGET_INVALID, (state as SourceSyncListenState.Failed).code)
        assertEquals(0, factory.openCalls)
        assertEquals("预算不是数字时不能当成 0 或缺省值", null, form.budgetMillis)

        val text = form.toString()
        assertTrue("预算原文照旧可读（它就是用户输入的数字）", text.contains("budget=12s"))
        assertEquals("口令仍然打码", false, text.contains("s3cret"))
    }

    // ------- W6-106 监听路径的固定日志（措辞见 SourceSyncListenLog） -------

    private fun memoryLogs(): Pair<MemoryLogger, LogBuffer> {
        val buffer = LogBuffer()
        return MemoryLogger(buffer) to buffer
    }

    @Test
    fun aStartedListenerLeavesExactlyOneInfoLine() = runTest {
        val (logger, buffer) = memoryLogs()
        val controller = SourceSyncListenerController(
            FakeFactory(opened = opened(FakeSession(port = 45_678))),
            logger,
        ) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm()))

        assertEquals(
            listOf("[I][NETWORK] sync-listen-started:port=45678:tls=false"),
            buffer.snapshot(),
        )
    }

    @Test
    fun theStartedLineSaysTlsWhenTheListenerPresentsTls() = runTest {
        val (logger, buffer) = memoryLogs()
        val factory = FakeFactory(
            availability = SourceSyncListenTlsAvailability(available = true, note = "ok"),
            opened = opened(FakeSession(port = 9_001, tlsEnabled = true, certificateFingerprint = LISTEN_PIN)),
        )
        val controller = SourceSyncListenerController(factory, logger) { _, _ -> }

        assertTrue(controller.start(SourceSyncListenForm(port = "9001", tls = true)))

        assertEquals(listOf("[I][NETWORK] sync-listen-started:port=9001:tls=true"), buffer.snapshot())
    }

    @Test
    fun aReceivedEnvelopeLeavesOneLineWithThePeerAndTheReceiptFlag() = runTest {
        val (logger, buffer) = memoryLogs()
        val listener = FakeSession(
            outcome = SourceSyncTransferImportOutcome.Imported(
                result = SourceSyncReceiveEcho(
                    deviceId = "device-a",
                    snapshotId = "snap-1",
                    envelopeContentHash = "hash-1",
                ),
                envelopeJson = envelopeText(),
                receiptWritten = true,
            ),
        )
        val controller = SourceSyncListenerController(
            FakeFactory(opened = opened(listener)),
            logger,
        ) { _, _ -> }

        controller.start(SourceSyncListenForm(receipt = true))
        assertTrue(controller.awaitOnce())

        assertEquals(
            listOf(
                "[I][NETWORK] sync-listen-started:port=45678:tls=false",
                "[I][NETWORK] sync-listen-received:device=device-a:receipt=true",
            ),
            buffer.snapshot(),
        )
    }

    @Test
    fun anIdentityLessEnvelopeSaysUnknownDeviceInsteadOfABlank() = runTest {
        val (logger, buffer) = memoryLogs()
        val listener = FakeSession(outcome = imported(envelopeText(), deviceId = null))
        val controller = SourceSyncListenerController(
            FakeFactory(opened = opened(listener)),
            logger,
        ) { _, _ -> }

        controller.start(SourceSyncListenForm())
        assertTrue(controller.awaitOnce())

        assertEquals(
            listOf(
                "[I][NETWORK] sync-listen-started:port=45678:tls=false",
                "[I][NETWORK] sync-listen-received:device=unknown-device:receipt=false",
            ),
            buffer.snapshot(),
        )
    }

    @Test
    fun aRejectedEnvelopeAndAFailedWaitEachLeaveTheirCode() = runTest {
        val (logger, buffer) = memoryLogs()
        val listener = FakeSession(outcome = rejectedEnvelope("malformed-json", "文档不是 JSON。"))
        val controller = SourceSyncListenerController(
            FakeFactory(opened = opened(listener)),
            logger,
        ) { _, _ -> }

        controller.start(SourceSyncListenForm())
        assertTrue(controller.awaitOnce())
        listener.outcome = transferred(SourceSyncTransferReasons.TRANSFER_TIMEOUT, "对端在超时前没有响应")
        assertTrue(controller.awaitOnce())

        assertEquals(
            listOf(
                "[I][NETWORK] sync-listen-started:port=45678:tls=false",
                "[W][NETWORK] sync-listen-rejected:reason=malformed-json",
                "[W][NETWORK] sync-listen-failed:code=" + SourceSyncTransferReasons.TRANSFER_TIMEOUT,
            ),
            buffer.snapshot(),
        )
        val joined = buffer.snapshot().joinToString("\n")
        assertFalse("界面用的说明不进日志", joined.contains("文档不是 JSON。"))
        assertFalse("异常措辞不进日志", joined.contains("对端在超时前没有响应"))
    }

    @Test
    fun cancelledWaitsAddUpAndStopLeavesOneLine() = runTest {
        val (logger, buffer) = memoryLogs()
        val listener = FakeSession()
        val controller = SourceSyncListenerController(
            FakeFactory(opened = opened(listener)),
            logger,
        ) { _, _ -> }
        controller.start(SourceSyncListenForm())

        repeat(2) {
            val gate = CompletableDeferred<Unit>()
            listener.gate = gate
            val cancellation = SourceSyncCancellation()
            val job = launch { controller.awaitOnce(cancellation) }
            runCurrent()
            cancellation.cancel()
            listener.outcome = transferred(com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED)
            gate.complete(Unit)
            job.join()
        }
        controller.stop()
        controller.stop()

        assertEquals(
            listOf(
                "[I][NETWORK] sync-listen-started:port=45678:tls=false",
                "[I][NETWORK] sync-listen-cancelled:count=1:reason=" + com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED,
                "[I][NETWORK] sync-listen-cancelled:count=2:reason=" + com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED,
                "[I][NETWORK] sync-listen-stopped:port=45678",
            ),
            buffer.snapshot(),
        )
    }

    @Test
    fun aRefusedStartLeavesAFailedLineWithoutTheDetail() = runTest {
        val (logger, buffer) = memoryLogs()
        val controller = SourceSyncListenerController(
            FakeFactory(opened = opened(FakeSession())),
            logger,
        ) { _, _ -> }

        assertFalse(controller.start(SourceSyncListenForm(port = "0")))

        assertEquals(
            listOf("[W][NETWORK] sync-listen-failed:code=" + SourceSyncListenerController.PORT_INVALID),
            buffer.snapshot(),
        )
    }

    @Test
    fun noLineEverQuotesASecretTheFingerprintOrTheBudget() = runTest {
        val (logger, buffer) = memoryLogs()
        val factory = FakeFactory(
            availability = SourceSyncListenTlsAvailability(available = true, note = "ok"),
            opened = opened(FakeSession(port = 9_100, tlsEnabled = true, certificateFingerprint = LISTEN_PIN)),
        )
        val controller = SourceSyncListenerController(factory, logger) { _, _ -> }

        assertTrue(
            controller.start(
                SourceSyncListenForm(port = "9100", tls = true, auth = true, secret = "s3cret", budget = "30000"),
            ),
        )

        val lines = buffer.snapshot()
        val joined = lines.joinToString("\n")
        assertEquals("这一场只有一行（开监听）", 1, lines.size)
        assertFalse("口令不进日志", joined.contains("s3cret"))
        assertFalse("证书指纹不进日志", joined.contains(LISTEN_PIN))
        assertFalse("等待预算不进日志", joined.contains("30000"))
    }
    private suspend fun seededSession(): Seeded {
        val preview = FakePreviewPort(readyPreview())
        val apply = FakeApplyPort(appliedResult())
        val session = SourceSyncImportSession(preview, apply)
        session.loadEnvelope(envelopeText(), "manual-envelope.json")
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)
        return Seeded(session, preview, apply)
    }

    private data class Seeded(
        val session: SourceSyncImportSession,
        val preview: FakePreviewPort,
        val apply: FakeApplyPort,
    )
}