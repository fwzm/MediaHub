package com.mediahub.core.common.sync

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-58：本地取消把手在**真实回环 socket** 上的语义。
 *
 * 为什么必须有这一层：取消的价值全在「立刻」两个字上。分块收帧是阻塞读，
 * 只 cancel() 协程不会让读返回——界面会一直停在「接收中」直到读超时（缺省 15 秒）。
 * 这些用例用一枚裸 ServerSocket 当对端（accept 之后既不发字节也不关连接），
 * 让客户端真的阻塞在 read 上，再从主线程取消，从而证明：
 *
 * - 取消能把阻塞的读解开，并在**毫秒级**给出本地码 transfer-cancelled（不是等超时，也不是 io-failed）；
 * - 这个码**不在**跨机词汇表里（两端各 10 个码的集合一个字都不动）；
 * - 取消是粘的、幂等的；没取消的把手不干扰正常接收；取消之后换一个新把手还能照常收。
 *
 * 纪律：本类只连 127.0.0.1 / ::1，不 bind、不 dial 任何非回环地址（那会触 Windows 防火墙弹窗），
 * 因此「取消在跨机场景下的行为」仍是 NOT_RUN。
 */
class SourceSyncTransferCancelTest {

    private fun payload(size: Int = 4096): ByteArray = ByteArray(size) { index -> ((index * 31 + 7) and 0xFF).toByte() }

    private fun failureOf(result: SourceSyncTransferResult): SourceSyncTransferResult.Failed {
        if (result is SourceSyncTransferResult.Ok) {
            throw AssertionError("本应被取消，实际收下了 " + result.envelope.size + " 字节")
        }
        return result as SourceSyncTransferResult.Failed
    }

    /**
     * 对端 accept 之后什么都不做：不发字节、不关连接。
     *
     * @return 「已经 accept 到一条连接」的闩，主线程等它再取消，避免取消发生在读之前。
     */
    private fun silentPeer(server: ServerSocket): CountDownLatch {
        val accepted = CountDownLatch(1)
        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    accepted.countDown()
                    socket.soTimeout = 20_000
                    val scratch = ByteArray(512)
                    while (socket.getInputStream().read(scratch) >= 0) {
                        // 读掉即可：本类要的正是「对端不说话也不关」。
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "w6-58-silent-peer"
        }
        thread.start()
        return accepted
    }

    /** 对端把一封信封的帧写完就关连接（正常发送方的最小形态）。 */
    private fun writingPeer(server: ServerSocket, envelope: ByteArray): CountDownLatch {
        val accepted = CountDownLatch(1)
        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    accepted.countDown()
                    socket.getOutputStream().write(SourceSyncTransfer.encode(envelope, null))
                    socket.getOutputStream().flush()
                    socket.shutdownOutput()
                }
            }
        }.apply {
            isDaemon = true
            name = "w6-58-writing-peer"
        }
        thread.start()
        return accepted
    }

    /** 在后台线程里跑一次 receiveFrom；主线程负责取消。 */
    private class ReceiveCall(
        private val port: Int,
        private val cancel: SourceSyncCancellation?,
        readTimeoutMs: Int = 10_000,
    ) {
        private val result = AtomicReference<SourceSyncTransferResult?>(null)
        private val failure = AtomicReference<Throwable?>(null)
        private val client = SourceSyncTransferClient(connectTimeoutMs = 5_000, readTimeoutMs = readTimeoutMs)
        private val thread = Thread {
            try {
                result.set(client.receiveFrom(host = "127.0.0.1", port = port, cancel = cancel))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            isDaemon = true
            name = "w6-58-receive"
        }

        fun start(): ReceiveCall {
            thread.start()
            return this
        }

        fun isDone(): Boolean = !thread.isAlive

        fun await(timeoutMs: Long = 30_000): SourceSyncTransferResult {
            thread.join(timeoutMs)
            assertFalse("接收线程没有在超时内结束（取消没把阻塞的读解开？）", thread.isAlive)
            failure.get()?.let { throw AssertionError("接收线程抛了异常", it) }
            return result.get() ?: throw AssertionError("接收线程没有给出结果")
        }
    }

    @Test
    fun aBlockedReadEndsAsTheLocalCodeWithinMilliseconds() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = silentPeer(server)
            val cancellation = SourceSyncCancellation()
            val call = ReceiveCall(server.localPort, cancellation).start()
            assertTrue("对端没有在 10 秒内 accept 到连接", accepted.await(10, TimeUnit.SECONDS))
            // 给读线程一点时间真正阻塞在 read 上：这一步是刻意的，否则取消可能发生在拨号之前，
            // 用例就只证明了「取消前先取消」，证明不了「阻塞的读被解开」。
            Thread.sleep(200)
            assertFalse("取消之前不该有结果（对端一个字都没发）", call.isDone())
            val startedAt = System.nanoTime()
            cancellation.cancel()
            val result = call.await()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            val failure = failureOf(result)
            assertEquals(SourceSyncLocalReasons.CANCELLED, failure.reason)
            assertTrue("取消后必须毫秒级返回，实际 " + elapsedMs + " ms（读超时是 10 秒）", elapsedMs < 2_000)
            assertTrue(
                "诊断句要说清是本地取消，实际：" + failure.message,
                failure.message?.contains("本地取消") == true,
            )
            assertTrue("取消是粘的", cancellation.isCancelled)
        }
    }

    @Test
    fun cancellingBeforeTheDialStillYieldsTheLocalCode() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val cancellation = SourceSyncCancellation()
            cancellation.cancel()
            val result = ReceiveCall(server.localPort, cancellation).start().await()
            // 连接在 attach 时就被关掉：connect 抛 SocketException，不能因此报成 connect-failed。
            assertEquals(SourceSyncLocalReasons.CANCELLED, failureOf(result).reason)
        }
    }

    @Test
    fun cancelIsIdempotentAndSticky() {
        val cancellation = SourceSyncCancellation()
        assertFalse(cancellation.isCancelled)
        cancellation.cancel()
        cancellation.cancel()
        cancellation.cancel()
        assertTrue("重复取消不会抛异常，也不会变回未取消", cancellation.isCancelled)
    }

    @Test
    fun aHandleThatWasNeverCancelledDoesNotDisturbANormalReceive() {
        val envelope = payload()
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = writingPeer(server, envelope)
            val cancellation = SourceSyncCancellation()
            val result = ReceiveCall(server.localPort, cancellation).start().await()
            assertTrue("对端没有在 10 秒内 accept 到连接", accepted.await(10, TimeUnit.SECONDS))
            val ok = result as? SourceSyncTransferResult.Ok
                ?: throw AssertionError("没取消的把手不该改变正常路径：" + failureOf(result).reason)
            assertEquals("收下的信封必须逐字节相同", envelope.size, ok.envelope.size)
            assertTrue(envelope.contentEquals(ok.envelope))
            assertFalse("正常收完之后仍然没有被取消", cancellation.isCancelled)
            // 收完之后再取消是 no-op（连接已经摘掉了，不该抛出任何异常）。
            cancellation.cancel()
            assertTrue(cancellation.isCancelled)
        }
    }

    @Test
    fun aFreshHandleWorksRightAfterACancellation() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { firstServer ->
            val accepted = silentPeer(firstServer)
            val first = SourceSyncCancellation()
            val call = ReceiveCall(firstServer.localPort, first).start()
            assertTrue(accepted.await(10, TimeUnit.SECONDS))
            Thread.sleep(100)
            first.cancel()
            assertEquals(SourceSyncLocalReasons.CANCELLED, failureOf(call.await()).reason)
        }
        // 取消过的把手不污染下一次：新把手 + 新对端照常收下信封。
        val envelope = payload(2048)
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { secondServer ->
            writingPeer(secondServer, envelope)
            val result = ReceiveCall(secondServer.localPort, SourceSyncCancellation()).start().await()
            val ok = result as? SourceSyncTransferResult.Ok
                ?: throw AssertionError("取消之后换新把手应当能再收一次：" + failureOf(result).reason)
            assertTrue(envelope.contentEquals(ok.envelope))
        }
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
}
