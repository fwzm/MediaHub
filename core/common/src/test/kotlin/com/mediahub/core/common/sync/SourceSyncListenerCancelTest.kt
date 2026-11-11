package com.mediahub.core.common.sync

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-61：**监听端**本地取消「这一次接收」的语义（W6-58 是客户端那一侧）。
 *
 * 病灶与客户端侧同源但更隐蔽：监听端在等 accept 时是阻塞的，close() 虽然能解开它，
 * 但 close 会**永久废掉整个监听**，而且那个阻塞线程拿到的是 listener-failed——
 * 于是界面只有两个选择：一直等到 accept 超时（缺省 15 秒），或者放弃监听端口再重开。
 *
 * 这些用例证明取消是第三种、也是正确的一种：
 *
 * - 等 accept 期间取消，**毫秒级**返回本地码 transfer-cancelled，且 [SourceSyncTransferListener.isClosed] 仍为 false；
 * - 已经连上、正卡在读帧上时取消，同样毫秒级返回本地码（不是 io-failed、不是 transfer-timeout）；
 * - 取消之后同一个监听**还能再收一封信封**（这是 close 做不到的，也是本里程碑的全部价值）；
 * - listener-failed 不再由「取消」产生，只剩「真的 close 过」一种来源；
 * - 本地码不进跨机词汇表（W6-47 结论 A：两端各 10 个码的集合一个字都不动）。
 *
 * 纪律：本类只绑 127.0.0.1，不 bind、不 dial 任何非回环地址（那会触发 Windows 防火墙弹窗），
 * 因此「跨机场景下取消监听端等待」仍是 NOT_RUN。
 */
class SourceSyncListenerCancelTest {

    private fun payload(size: Int = 4096): ByteArray = ByteArray(size) { index -> ((index * 17 + 11) and 0xFF).toByte() }

    private fun opened(acceptTimeoutMs: Int = 30_000, readTimeoutMs: Int = 10_000): SourceSyncTransferListener {
        val started = SourceSyncTransferListener.open(
            port = 0,
            acceptTimeoutMs = acceptTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
        assertTrue(
            "回环监听应该能起来，实际：" + when (started) {
                is SourceSyncListenerStart.Started -> "ok port=" + started.listener.port
                is SourceSyncListenerStart.Failed -> started.code + " / " + (started.detail ?: "")
            },
            started is SourceSyncListenerStart.Started,
        )
        return (started as SourceSyncListenerStart.Started).listener
    }

    private fun codeOf(result: SourceSyncListenerTransfer): String {
        assertTrue(
            "期待失败，实际收到了信封（" + (result as? SourceSyncListenerTransfer.Received)?.bytes + " 字节）",
            result is SourceSyncListenerTransfer.Failed,
        )
        return (result as SourceSyncListenerTransfer.Failed).code
    }

    private fun detailOf(result: SourceSyncListenerTransfer): String? =
        (result as? SourceSyncListenerTransfer.Failed)?.detail

    /** 在后台线程里跑一次 receiveOnce；主线程负责取消。 */
    private class ReceiveCall(
        private val listener: SourceSyncTransferListener,
        private val cancel: SourceSyncCancellation?,
    ) {
        private val result = AtomicReference<SourceSyncListenerTransfer?>(null)
        private val failure = AtomicReference<Throwable?>(null)
        private val thread = Thread {
            try {
                result.set(listener.receiveOnce(cancel = cancel))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            isDaemon = true
            name = "w6-61-listener-cancel"
        }

        fun start(): ReceiveCall {
            thread.start()
            return this
        }

        fun isDone(): Boolean = !thread.isAlive

        fun await(timeoutMs: Long = 30_000): SourceSyncListenerTransfer {
            thread.join(timeoutMs)
            assertFalse("监听线程没有在超时内结束（取消没把阻塞的 accept / 读解开？）", thread.isAlive)
            failure.get()?.let { throw AssertionError("监听线程抛了异常", it) }
            return result.get() ?: throw AssertionError("监听线程没有给出结果")
        }
    }

    @Test
    fun cancellingWhileWaitingForAcceptReturnsTheLocalCodeWithinMilliseconds() {
        val listener = opened(acceptTimeoutMs = 30_000)
        try {
            val cancellation = SourceSyncCancellation()
            val call = ReceiveCall(listener, cancellation).start()
            // 给监听线程时间真正阻塞进 accept：否则用例只证明了"取消前先取消"。
            Thread.sleep(200)
            assertFalse("取消之前不该有结果（根本没有对端连上来）", call.isDone())
            val startedAt = System.nanoTime()
            cancellation.cancel()
            val result = call.await()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertEquals(SourceSyncLocalReasons.CANCELLED, codeOf(result))
            assertTrue("取消后必须毫秒级返回，实际 " + elapsedMs + " ms（accept 预算 30 秒）", elapsedMs < 2_000)
            assertTrue(
                "诊断句要说清是本地取消且监听还开着，实际：" + detailOf(result),
                detailOf(result)?.contains("本地取消") == true,
            )
            assertFalse("取消不是 close：监听必须仍然开着", listener.isClosed)
            assertTrue("取消是粘的", cancellation.isCancelled)
        } finally {
            listener.close()
        }
    }

    @Test
    fun cancellingDuringABlockedReadAlsoYieldsTheLocalCode() {
        val listener = opened(acceptTimeoutMs = 30_000, readTimeoutMs = 10_000)
        try {
            // 对端连上来但一个字都不发：监听端会卡在嗅探/读帧上，读超时是 10 秒。
            val silentPeer = Socket(InetAddress.getLoopbackAddress(), listener.port)
            val cancellation = SourceSyncCancellation()
            val call = ReceiveCall(listener, cancellation).start()
            try {
                // 等到监听端真的把这条连接 accept 进来并发起读。
                Thread.sleep(300)
                assertFalse("取消之前不该有结果（对端一个字都没发）", call.isDone())
                val startedAt = System.nanoTime()
                cancellation.cancel()
                val result = call.await()
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                assertEquals(SourceSyncLocalReasons.CANCELLED, codeOf(result))
                assertTrue("取消后必须毫秒级返回，实际 " + elapsedMs + " ms（读超时是 10 秒）", elapsedMs < 2_000)
                assertNotEquals(
                    "取消不许被翻译成 io-failed（那是真出错的码）",
                    SourceSyncTransferReasons.IO_FAILED,
                    codeOf(result),
                )
                assertFalse("取消不是 close：监听必须仍然开着", listener.isClosed)
            } finally {
                silentPeer.close()
            }
        } finally {
            listener.close()
        }
    }

    @Test
    fun cancellingBeforeTheWaitStartsStillYieldsTheLocalCode() {
        val listener = opened(acceptTimeoutMs = 30_000)
        try {
            val cancellation = SourceSyncCancellation()
            cancellation.cancel()
            // 先把把手置位再等：第一片就会看到取消，一个 accept 都不做。
            val result = ReceiveCall(listener, cancellation).start().await()
            assertEquals(SourceSyncLocalReasons.CANCELLED, codeOf(result))
            assertFalse(listener.isClosed)
        } finally {
            listener.close()
        }
    }

    @Test
    fun theListenerStillAcceptsAfterACancelledWait() {
        val envelope = payload()
        val listener = opened(acceptTimeoutMs = 30_000)
        try {
            val cancellation = SourceSyncCancellation()
            val call = ReceiveCall(listener, cancellation).start()
            Thread.sleep(200)
            cancellation.cancel()
            assertEquals(SourceSyncLocalReasons.CANCELLED, codeOf(call.await()))

            // 这就是 close 做不到的事：同一个监听、同一个端口，换一枚新把手再等一次，能收下信封。
            val sent = SendCall(listener.port, envelope).start()
            val received = listener.receiveOnce(cancel = SourceSyncCancellation())
            val ok = received as? SourceSyncListenerTransfer.Received
                ?: throw AssertionError("取消之后应当还能再收一次，实际：" + codeOf(received))
            assertTrue("收下的信封必须逐字节相同", envelope.contentEquals(ok.envelope))
            assertEquals(envelope.size, ok.bytes)
            sent.await()
            assertFalse("两次之后监听仍应可用", listener.isClosed)
        } finally {
            listener.close()
        }
    }

    @Test
    fun aHandleThatWasNeverCancelledDoesNotDisturbANormalReceive() {
        val envelope = payload(2048)
        val listener = opened(acceptTimeoutMs = 30_000)
        try {
            val cancellation = SourceSyncCancellation()
            val sent = SendCall(listener.port, envelope).start()
            val received = listener.receiveOnce(cancel = cancellation)
            val ok = received as? SourceSyncListenerTransfer.Received
                ?: throw AssertionError("没取消的把手不该改变正常路径：" + codeOf(received))
            assertTrue(envelope.contentEquals(ok.envelope))
            sent.await()
            assertFalse("正常收完之后仍然没有被取消", cancellation.isCancelled)
        } finally {
            listener.close()
        }
    }

    @Test
    fun closingTheListenerIsStillReportedAsListenerFailedNotAsACancellation() {
        val listener = opened(acceptTimeoutMs = 30_000)
        val call = ReceiveCall(listener, SourceSyncCancellation()).start()
        Thread.sleep(200)
        listener.close()
        val result = call.await()
        // W6-61 之后 listener-failed 只剩"真的 close 过"这一种来源：取消有自己的码，两者不许混。
        assertEquals(SourceSyncTransferReasons.LISTENER_FAILED, codeOf(result))
        assertNotEquals(SourceSyncLocalReasons.CANCELLED, codeOf(result))
        assertTrue(listener.isClosed)
    }

    @Test
    fun theLocalCodeIsNeverPartOfTheCrossMachineVocabulary() {
        // W6-47 契约（结论 A）：跨机码表是两端逐字同名同义的固定集合，本地取消不进去。
        assertEquals(10, SourceSyncTransferReasons.ALL.size)
        assertFalse(
            "transfer-cancelled 是本地码，不许进跨机词汇表",
            SourceSyncTransferReasons.ALL.contains(SourceSyncLocalReasons.CANCELLED),
        )
        assertEquals("transfer-cancelled", SourceSyncLocalReasons.CANCELLED)
    }

    /** 用真实客户端把一封信封发到监听端。 */
    private class SendCall(
        private val port: Int,
        private val envelope: ByteArray,
    ) {
        private val result = AtomicReference<SourceSyncTransferResult?>(null)
        private val failure = AtomicReference<Throwable?>(null)
        private val client = SourceSyncTransferClient(connectTimeoutMs = 5_000, readTimeoutMs = 10_000)
        private val thread = Thread {
            try {
                result.set(client.sendTo("127.0.0.1", port, envelope, null, SourceSyncTlsOptions.Off, null))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            isDaemon = true
            name = "w6-61-send"
        }

        fun start(): SendCall {
            thread.start()
            return this
        }

        fun await() {
            thread.join(30_000)
            assertFalse("发送线程没有在超时内结束", thread.isAlive)
            failure.get()?.let { throw AssertionError("发送线程抛了异常", it) }
        }
    }
}
