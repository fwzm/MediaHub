package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncCancellation
import com.mediahub.core.database.sync.SourceSyncImportResult
import com.mediahub.core.database.sync.SourceSyncTransferImportOutcome
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-58：「取消接收」的 JVM 证据（纯 JVM，不开设备、不碰 Compose）。
 *
 * 与 [SourceSyncSendLauncherTest] 同源：ViewModel 的构造依赖 Room / Keystore / provider 注册表，
 * 纯 JVM 造不出来，所以把「一次接收 = 一个可取消的 Job + 一个取消把手」这层记账单独测。
 * 与发送那条的差别正是本类要钉住的东西：**取消必须把把手也关掉**（否则阻塞的 socket 读会一直挂着，
 * 界面停在「接收中」直到读超时），所以每条取消断言同时看三件事：状态回 Idle、没有稳定码、
 * 交给端口的那个把手确实被置为已取消。
 *
 * 造「在飞」的手法与发送那条一致：假端口在 started 信号之后 [awaitCancellation]，
 * 于是用例能在状态确实是 Receiving、单飞标记确实被占着的时候取消，而不是靠 sleep 猜时机。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSyncReceiveLauncherTest {

    @Test
    fun launchStartsTheReceiveAndMarksItInFlight() = runTest {
        val port = FakeReceivePort(hang = true)
        val controller = SourceSyncReceiveController(port) { _, _ -> }
        val launcher = SourceSyncReceiveLauncher(this) { form, cancel -> controller.receive(form, cancel) }

        try {
            assertTrue(launcher.launch(form()))
            port.started.await()
            assertTrue(launcher.isActive)
            assertEquals(SourceSyncReceiveState.Receiving, controller.state.value)
        } finally {
            launcher.cancel()?.join()
        }
        assertEquals(SourceSyncReceiveState.Idle, controller.state.value)
    }

    @Test
    fun cancelDuringTheTransportClosesTheHandleAndReturnsToIdle() = runTest {
        val port = FakeReceivePort(hang = true)
        val controller = SourceSyncReceiveController(port) { _, _ -> }
        val launcher = SourceSyncReceiveLauncher(this) { form, cancel -> controller.receive(form, cancel) }

        assertTrue(launcher.launch(form()))
        port.started.await()
        val handle = port.cancel
        assertNotNull("端口必须收到一个取消把手", handle)
        assertFalse("还没取消时把手是干净的", handle!!.isCancelled)

        val cancelled = launcher.cancel()
        assertNotNull("在飞时必须真的取消了一条", cancelled)
        cancelled!!.join()

        // 关键：把手被关掉了——真实实现里这一步会 close socket，让阻塞的读立刻返回。
        assertTrue("取消必须关掉连接（只 cancel 协程会让界面等到读超时）", handle.isCancelled)
        assertEquals(SourceSyncReceiveState.Idle, controller.state.value)
        assertFalse(launcher.isActive)
        assertNull("取消不产生结果行", SourceSyncReceiveCopy.lineOf(controller.state.value))
        assertNull("取消不产生稳定码（本地码也不留）", SourceSyncReceiveCopy.codeOf(controller.state.value))
    }

    @Test
    fun cancelWhenNothingWasEverReceivedIsANoop() = runTest {
        val controller = SourceSyncReceiveController(FakeReceivePort()) { _, _ -> }
        val launcher = SourceSyncReceiveLauncher(this) { form, cancel -> controller.receive(form, cancel) }

        assertNull(launcher.cancel())
        assertFalse(launcher.isActive)
        assertEquals(SourceSyncReceiveState.Idle, controller.state.value)
        assertNull(SourceSyncReceiveCopy.codeOf(controller.state.value))
    }

    @Test
    fun cancelAfterTheCancelledReceiveFinishedIsStillANoop() = runTest {
        val port = FakeReceivePort(hang = true)
        val controller = SourceSyncReceiveController(port) { _, _ -> }
        val launcher = SourceSyncReceiveLauncher(this) { form, cancel -> controller.receive(form, cancel) }

        assertTrue(launcher.launch(form()))
        port.started.await()
        launcher.cancel()!!.join()

        assertNull("那条 Job 已经收尾：再点一次取消什么都不做", launcher.cancel())
        assertEquals(SourceSyncReceiveState.Idle, controller.state.value)
    }

    @Test
    fun aSecondLaunchWhileInFlightIsIgnored() = runTest {
        val port = FakeReceivePort(hang = true)
        val controller = SourceSyncReceiveController(port) { _, _ -> }
        val launcher = SourceSyncReceiveLauncher(this) { form, cancel -> controller.receive(form, cancel) }

        try {
            assertTrue(launcher.launch(form()))
            port.started.await()
            assertFalse("在飞时第二次点击不该再起一条", launcher.launch(form(port = "8943")))
            assertEquals(1, port.calls.get())
        } finally {
            launcher.cancel()?.join()
        }
    }

    @Test
    fun cancellingTwiceOnlyCancelsTheFirstTime() = runTest {
        val port = FakeReceivePort(hang = true)
        val controller = SourceSyncReceiveController(port) { _, _ -> }
        val launcher = SourceSyncReceiveLauncher(this) { form, cancel -> controller.receive(form, cancel) }

        assertTrue(launcher.launch(form()))
        port.started.await()
        val first = launcher.cancel()
        assertNotNull(first)
        assertNull("同一条只能取消一次（第二次已经不在飞）", launcher.cancel())
        first!!.join()
        assertEquals(SourceSyncReceiveState.Idle, controller.state.value)
    }

    @Test
    fun clearBetweenReceivesIsHarmlessAndCancelAfterItIsANoop() = runTest {
        val port = FakeReceivePort(hang = true)
        val controller = SourceSyncReceiveController(port) { _, _ -> }
        val launcher = SourceSyncReceiveLauncher(this) { form, cancel -> controller.receive(form, cancel) }

        launcher.clear()
        assertNull(launcher.cancel())
        assertEquals("clear 只丢引用，不该取消任何东西", 0, port.calls.get())

        assertTrue(launcher.launch(form()))
        port.started.await()
        try {
            assertTrue(launcher.isActive)
        } finally {
            launcher.cancel()?.join()
        }
        launcher.clear()
        assertEquals(SourceSyncReceiveState.Idle, controller.state.value)
    }

    @Test
    fun eachLaunchGetsAFreshHandleSoAnOldCancellationDoesNotLeak() = runTest {
        val port = FakeReceivePort(hang = true)
        var loads = 0
        val controller = SourceSyncReceiveController(port) { _, _ -> loads++ }
        val launcher = SourceSyncReceiveLauncher(this) { form, cancel -> controller.receive(form, cancel) }

        assertTrue(launcher.launch(form()))
        port.started.await()
        val firstHandle = port.cancel
        launcher.cancel()!!.join()
        assertTrue(firstHandle!!.isCancelled)

        // 第二次：新把手（没被取消过），端口不挂了，正常收下。
        // launch 只是把协程排进 testScheduler，必须推进调度器端口才真的被调到（否则读到的还是第一枚把手）。
        port.hang = false
        assertTrue(launcher.launch(form(port = "8943")))
        advanceUntilIdle()
        assertEquals("第二次必须真的调到端口", 2, port.handles.size)
        val secondHandle = port.handles[1]
        assertNotSame("每次 launch 都必须新建把手", firstHandle, secondHandle)
        assertFalse("旧把手被取消过，新把手必须是干净的", secondHandle!!.isCancelled)
        assertSame(secondHandle, port.cancel)
        assertEquals("两次都真的调过端口", 2, port.calls.get())
        assertEquals(1, loads)
        assertTrue(controller.state.value is SourceSyncReceiveState.Received)
    }

    // ------------------------------------------------------------------ 夹具

    private fun form(port: String = "8942"): SourceSyncReceiveForm = SourceSyncReceiveForm(port = port)

    /**
     * 假接收端：hang=true 时在发出 started 信号后永远挂住，用来造「接收途中取消」；
     * 顺便把控制器交来的取消把手记下来，供用例断言它真的被关掉。
     */
    private class FakeReceivePort(var hang: Boolean = false) : SourceSyncReceivePort {

        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        /** 每次 receive 收到的把手，按调用顺序记下来（用来断言「每次 launch 都新建一枚」）。 */
        val handles = mutableListOf<SourceSyncCancellation?>()
        var cancel: SourceSyncCancellation? = null

        override suspend fun receive(
            host: String,
            port: Int,
            expectedChallenge: String?,
        ): SourceSyncTransferImportOutcome = receive(host, port, expectedChallenge, false, null)

        override suspend fun receive(
            host: String,
            port: Int,
            expectedChallenge: String?,
            allowExplicitHost: Boolean,
            cancel: SourceSyncCancellation?,
        ): SourceSyncTransferImportOutcome {
            calls.incrementAndGet()
            this.cancel = cancel
            handles.add(cancel)
            started.complete(Unit)
            if (hang) awaitCancellation()
            return SourceSyncTransferImportOutcome.Imported(
                envelopeJson = ENVELOPE_JSON,
                result = SourceSyncImportResult(deviceId = "windows-a"),
            )
        }
    }

    private companion object {
        const val ENVELOPE_JSON = "{\"schemaVersion\":1,\"deviceId\":\"windows-a\"}"
    }
}
