package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-61：监听端「一次等待 = 一个可取消的 Job + 一个取消把手」这层薄壳（纯 JVM）。
 *
 * 与 W6-58 的 [SourceSyncReceiveLauncherTest] 同一个理由：ViewModel 造不出来，
 * 而「取消是用户操作、不是失败」这条语义必须被 JVM 用例钉住。
 * 监听端与接收端的唯一区别是**取消不关监听**——所以这里额外钉住：
 * 取消只解阻塞这一次，把手的生命周期与 Job 一致，下一次 launch 一定是新把手。
 *
 * 真实的「解阻塞毫秒级返回」由 core:common 的 SourceSyncListenerCancelTest 在真 socket 上钉住，
 * 这里只钉记账。
 *
 * 注意作用域：Launcher 必须挂在 [TestScope.backgroundScope] 上。挂在 `this`（测试作用域）上时，
 * 「故意挂着等待取消」的那条 Job 会成为测试体的子协程，runTest 收尾时判定「还有活跃子协程」
 * 而报 UncompletedCoroutinesError——那不是产品缺陷，是把长活协程挂错了作用域（W6-61 首跑 3 红即此因）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSyncListenLauncherTest {

    /** 假的一次等待：可挂起（模拟阻塞在 accept），记录拿到的把手。 */
    private class FakeAwait {
        val handles = mutableListOf<SourceSyncCancellation>()
        var calls = 0
        var hang = true
        var result = true
        val started = CompletableDeferred<Unit>()

        val body: suspend (SourceSyncCancellation) -> Boolean = { cancel ->
            calls++
            handles.add(cancel)
            started.complete(Unit)
            if (hang) awaitCancellation()
            // 取消后控制器给的是「正在监听」，也就是 true。
            result
        }

        val last: SourceSyncCancellation? get() = handles.lastOrNull()
    }

    @Test
    fun launchStartsTheWaitAndMarksItInFlight() = runTest {
        val fake = FakeAwait()
        val launcher = SourceSyncListenLauncher(backgroundScope) { fake.body(it) }

        assertFalse("还没起过", launcher.isActive)
        assertTrue("第一次 launch 应当起得来", launcher.launch())
        runCurrent()

        assertTrue("job 在飞", launcher.isActive)
        assertEquals(1, fake.calls)
        assertTrue("把手必须交给控制器那一层", fake.last != null)
    }

    @Test
    fun aSecondLaunchWhileInFlightIsIgnored() = runTest {
        val fake = FakeAwait()
        val launcher = SourceSyncListenLauncher(backgroundScope) { fake.body(it) }

        assertTrue(launcher.launch())
        runCurrent()
        assertFalse("已有一条在飞时不排队、不重复等", launcher.launch())
        runCurrent()

        assertEquals(1, fake.calls)
    }

    @Test
    fun cancelClosesTheHandleAndEndsTheJob() = runTest {
        val fake = FakeAwait()
        val launcher = SourceSyncListenLauncher(backgroundScope) { fake.body(it) }

        assertTrue(launcher.launch())
        runCurrent()
        val handle = fake.last!!
        assertFalse(handle.isCancelled)

        val cancelled = launcher.cancel()
        assertTrue("取消必须把把手置位（阻塞的 accept 靠它解开）", handle.isCancelled)
        assertTrue("取消必须交出 Job 让调用方 join", cancelled != null)
        cancelled?.join()
        assertFalse("取消之后就不在飞了", launcher.isActive)
    }

    @Test
    fun cancelWhenNothingWasEverStartedIsANoop() = runTest {
        val fake = FakeAwait()
        val launcher = SourceSyncListenLauncher(backgroundScope) { fake.body(it) }

        assertNull("没起过就没什么可取消的", launcher.cancel())
        assertEquals(0, fake.calls)
        assertFalse(launcher.isActive)
    }

    @Test
    fun cancelAfterTheWaitFinishedIsStillNoop() = runTest {
        val fake = FakeAwait()
        fake.hang = false
        val launcher = SourceSyncListenLauncher(backgroundScope) { fake.body(it) }

        assertTrue(launcher.launch())
        // 用 runCurrent 而不是 advanceUntilIdle：Launcher 挂在 backgroundScope 上，
        // advanceUntilIdle 不负责推进那边的活，job 会一直停在新起状态（W6-61 第二次实跑的 1 红即此）。
        runCurrent()
        assertFalse("已经收尾了", launcher.isActive)

        assertNull("结束之后取消是 no-op（不碰状态、不关任何东西）", launcher.cancel())
        assertEquals(1, fake.calls)
    }

    @Test
    fun eachLaunchGetsAFreshHandleSoAnOldCancellationDoesNotLeak() = runTest {
        val fake = FakeAwait()
        val launcher = SourceSyncListenLauncher(backgroundScope) { fake.body(it) }

        assertTrue(launcher.launch())
        runCurrent()
        val first = fake.last!!
        launcher.cancel()?.join()
        assertTrue(first.isCancelled)
        launcher.clear()

        // 第二次：必须是一枚**新的**把手，绝不复用被取消的那枚。
        assertTrue(launcher.launch())
        runCurrent()
        val second = fake.last!!
        assertNotSame("取消过的把手不许污染下一次等待", first, second)
        assertFalse("新把手必须是干净的", second.isCancelled)
        assertEquals(2, fake.calls)
    }

    @Test
    fun clearBetweenWaitsIsHarmless() = runTest {
        val fake = FakeAwait()
        val launcher = SourceSyncListenLauncher(backgroundScope) { fake.body(it) }

        // 页面重置时调用：只丢引用，不取消（取消必须是用户的显式动作）。
        launcher.clear()
        assertFalse(launcher.isActive)
        assertNull(launcher.cancel())
        assertEquals(0, fake.calls)
    }
}
