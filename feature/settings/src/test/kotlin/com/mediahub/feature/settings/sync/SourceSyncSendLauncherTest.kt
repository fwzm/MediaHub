package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncDeliveryOptions
import com.mediahub.core.common.sync.SourceSyncReceiptOptions
import com.mediahub.core.common.sync.SourceSyncTlsOptions
import com.mediahub.core.common.sync.SourceSyncTransferOptions
import com.mediahub.core.common.sync.SourceSyncTransferResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-49：「取消发送」的 JVM 证据（纯 JVM，不开设备、不碰 Compose）。
 *
 * 为什么测 [SourceSyncSendLauncher] 而不是直接测 ViewModel：ViewModel 的构造依赖 Room 仓库、
 * Keystore 凭据库与 provider 注册表，纯 JVM 造不出来；而「取消后回 Idle、不许伪造失败、取消后可再发」
 * 是用户可见语义，必须有确定性证据。ViewModel 的 [SourceSyncImportViewModel.send] /
 * [SourceSyncImportViewModel.cancelSend] 就是对本类的两行委托，所以这里钉住的就是那条真路径。
 *
 * 造「在飞」的手法：假导出端 / 假传输端在 started 信号之后 [awaitCancellation]，
 * 于是用例可以在状态确实是 Sending、单飞标记确实被占着的时候取消，而不是靠 sleep 猜时机。
 * 状态机不属于本类（在 [SourceSyncSendController] 里），所以每条取消断言都同时看状态与单飞标记。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSyncSendLauncherTest {

    @Test
    fun launchStartsTheSendAndMarksItInFlight() = runTest {
        val export = FakeExportPort(hang = true)
        val controller = SourceSyncSendController(export, FakeSendPort())
        val launcher = SourceSyncSendLauncher(this) { controller.send(it) }

        try {
            assertTrue(launcher.launch(form()))
            export.started.await()
            assertTrue(launcher.isActive)
            assertEquals(SourceSyncSendState.Sending, controller.state.value)
            assertTrue(controller.isSending)
        } finally {
            launcher.cancel()?.join()
        }
        assertEquals(SourceSyncSendState.Idle, controller.state.value)
    }

    @Test
    fun cancelDuringExportReturnsToIdleWithoutAnyFailure() = runTest {
        val export = FakeExportPort(hang = true)
        val send = FakeSendPort()
        val controller = SourceSyncSendController(export, send)
        val launcher = SourceSyncSendLauncher(this) { controller.send(it) }

        assertTrue(launcher.launch(form()))
        export.started.await()
        val cancelled = launcher.cancel()
        assertNotNull("在飞时必须真的取消了一条", cancelled)
        cancelled!!.join()

        assertEquals(SourceSyncSendState.Idle, controller.state.value)
        assertFalse(controller.isSending)
        assertFalse(launcher.isActive)
        assertEquals("导出阶段取消不该拨号", 0, send.calls.get())
        assertNull("取消不产生结果行", SourceSyncSendCopy.lineOf(controller.state.value))
        assertNull("取消不产生稳定码", SourceSyncSendCopy.codeOf(controller.state.value))
    }

    @Test
    fun cancelDuringTransportReturnsToIdleAndFreesTheSingleFlightGuard() = runTest {
        val send = FakeSendPort(hang = true)
        val controller = SourceSyncSendController(FakeExportPort(), send)
        val launcher = SourceSyncSendLauncher(this) { controller.send(it) }

        assertTrue(launcher.launch(form()))
        send.started.await()
        assertEquals(SourceSyncSendState.Sending, controller.state.value)
        val cancelled = launcher.cancel()
        assertNotNull(cancelled)
        cancelled!!.join()

        assertEquals(SourceSyncSendState.Idle, controller.state.value)
        assertFalse("取消后必须释放单飞标记", controller.isSending)

        // 取消之后可以立刻再发：这一步直接调控制器（suspend 调用在测试协程里确定性完成）。
        send.hang = false
        assertTrue(controller.send(form()))
        assertTrue(controller.state.value is SourceSyncSendState.Sent)
        assertEquals("两次都真的拨过号", 2, send.calls.get())
    }

    @Test
    fun cancelWhenNothingWasEverSentIsANoop() = runTest {
        val controller = SourceSyncSendController(FakeExportPort(), FakeSendPort())
        val launcher = SourceSyncSendLauncher(this) { controller.send(it) }

        assertNull(launcher.cancel())
        assertFalse(launcher.isActive)
        assertEquals(SourceSyncSendState.Idle, controller.state.value)
        assertNull(SourceSyncSendCopy.codeOf(controller.state.value))
    }

    @Test
    fun cancelAfterTheCancelledSendFinishedIsStillANoop() = runTest {
        val export = FakeExportPort(hang = true)
        val controller = SourceSyncSendController(export, FakeSendPort())
        val launcher = SourceSyncSendLauncher(this) { controller.send(it) }

        assertTrue(launcher.launch(form()))
        export.started.await()
        launcher.cancel()!!.join()

        // 那条 Job 已经收尾：再点一次取消什么都不做，也不会把 Idle 改成别的东西。
        assertNull(launcher.cancel())
        assertEquals(SourceSyncSendState.Idle, controller.state.value)
    }

    @Test
    fun aSecondLaunchWhileInFlightIsIgnored() = runTest {
        val export = FakeExportPort(hang = true)
        val controller = SourceSyncSendController(export, FakeSendPort())
        val launcher = SourceSyncSendLauncher(this) { controller.send(it) }

        try {
            assertTrue(launcher.launch(form()))
            export.started.await()
            assertFalse("在飞时第二次点击不该再起一条", launcher.launch(form(port = "9001")))
            assertEquals(1, export.calls.get())
        } finally {
            launcher.cancel()?.join()
        }
    }

    @Test
    fun cancellingTwiceOnlyCancelsTheFirstTime() = runTest {
        val export = FakeExportPort(hang = true)
        val controller = SourceSyncSendController(export, FakeSendPort())
        val launcher = SourceSyncSendLauncher(this) { controller.send(it) }

        assertTrue(launcher.launch(form()))
        export.started.await()
        val first = launcher.cancel()
        assertNotNull(first)
        assertNull("同一条只能取消一次（第二次已经不在飞）", launcher.cancel())
        first!!.join()
        assertEquals(SourceSyncSendState.Idle, controller.state.value)
    }

    @Test
    fun clearBetweenSendsIsHarmlessAndCancelAfterItIsANoop() = runTest {
        val export = FakeExportPort(hang = true)
        val controller = SourceSyncSendController(export, FakeSendPort())
        val launcher = SourceSyncSendLauncher(this) { controller.send(it) }

        launcher.clear()
        assertNull(launcher.cancel())

        assertTrue(launcher.launch(form()))
        export.started.await()
        try {
            assertTrue(launcher.isActive)
        } finally {
            launcher.cancel()?.join()
        }
        launcher.clear()
        assertEquals(SourceSyncSendState.Idle, controller.state.value)
    }

    // ------------------------------------------------------------------ 夹具

    private fun form(port: String = "9000"): SourceSyncSendForm = SourceSyncSendForm(port = port)

    /** 假导出端：hang=true 时在发出 started 信号后永远挂住，用来造「导出阶段取消」。 */
    private class FakeExportPort(private val hang: Boolean = false) : SourceSyncExportPort {

        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()

        override suspend fun export(): SourceSyncSendExport {
            calls.incrementAndGet()
            started.complete(Unit)
            if (hang) awaitCancellation()
            return SourceSyncSendExport(envelopeJson = ENVELOPE_JSON, sourceCount = 1)
        }
    }

    /** 假传输端：hang=true 时在发出 started 信号后永远挂住，用来造「发送阶段取消」。 */
    private class FakeSendPort(
        var hang: Boolean = false,
        var result: SourceSyncTransferResult = SourceSyncTransferResult.Ok(ByteArray(0), 2, null),
    ) : SourceSyncSendPort {

        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()

        override suspend fun send(
            host: String,
            port: Int,
            envelope: ByteArray,
            challenge: String?,
            tls: SourceSyncTlsOptions,
            auth: SourceSyncTransferOptions?,
            receipt: SourceSyncReceiptOptions,
            delivery: SourceSyncDeliveryOptions,
        ): SourceSyncTransferResult {
            calls.incrementAndGet()
            started.complete(Unit)
            if (hang) awaitCancellation()
            return result
        }
    }

    private companion object {
        const val ENVELOPE_JSON = "{\"schemaVersion\":1,\"deviceId\":\"android-a\"}"
    }
}
