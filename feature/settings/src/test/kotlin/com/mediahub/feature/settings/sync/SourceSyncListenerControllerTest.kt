package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SourceSyncSummary
import com.mediahub.core.common.sync.SourceSyncTransferListener
import com.mediahub.core.common.sync.SourceSyncTransferReasons
import com.mediahub.core.common.sync.SourceSyncTlsReasons
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.core.database.sync.AppliedSourceChange
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

/** 测试用指纹：与 core:common 的 tls 夹具（mediahub-sync-test.p12）同一枚，只当字符串用。 */
private val LISTEN_PIN: String = "2bb12d139178ee3b0f219e409a89c99b51be165914e21189eeb039b2e4dd37ce"

private fun imported(
    envelopeJson: String,
    deviceId: String? = "device-a",
): SourceSyncTransferImportOutcome.Imported = SourceSyncTransferImportOutcome.Imported(
    result = SourceSyncImportResult(
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

private fun appliedResult(): SourceSyncImportResult = SourceSyncImportResult(
    deviceId = "device-a",
    snapshotId = "snap-1",
    applied = listOf(
        AppliedSourceChange(
            serverId = "srv-1",
            candidate = createCandidate("sync-1"),
            contentHash = "hash-1",
        ),
    ),
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

    override suspend fun receiveOnce(): SourceSyncTransferImportOutcome {
        receiveCalls++
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

private class FakeApplyPort(private val result: SourceSyncImportResult) : SourceSyncApplyPort {

    var calls = 0

    override suspend fun apply(envelopeJson: String): SourceSyncImportResult {
        calls++
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
            result = SourceSyncImportResult(rejectedReason = "duplicate-sync-source-id"),
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
            result = SourceSyncImportResult(deviceId = "device-a", snapshotId = "snap-1"),
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
                result = SourceSyncImportResult(
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