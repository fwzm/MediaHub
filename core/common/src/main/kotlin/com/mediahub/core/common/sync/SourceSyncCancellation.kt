package com.mediahub.core.common.sync

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 「本地取消」的专用码（W6-58）。
 *
 * 刻意**不**放进 [SourceSyncTransferReasons]：那 10 个码是与 Windows 逐字同名同义的互操作词汇，
 * 两端任一侧增删都会被对方的测试发现；而「用户在本机点了取消」这件事对端根本无从得知
 * （它只会看到连接被关掉），也不该出现在任何跨机码表里。因此它是**本地码**：
 * 只可能由本端产生、永远不上线、界面按「取消不是失败」处理（不留码、状态回 Idle）。
 */
object SourceSyncLocalReasons {

    /** 本端在接收途中被本地取消（用户点了「取消接收」）。 */
    const val CANCELLED: String = "transfer-cancelled"
}

/**
 * 一次拨号接收的取消把手（W6-58）。
 *
 * 为什么需要它：分块收帧走的是**阻塞** socket 读，协程 Job 的取消只能在挂起点生效，
 * 而读 socket 不是挂起点——只 cancel() Job 的话，界面会一直停在「接收中」直到读超时（缺省 15 秒）。
 * 因此取消必须真的把这条连接关掉：本把手持有当前活跃 socket，[cancel] 先置位再关 socket，
 * 被阻塞的读立刻以 SocketException 返回，调用方随即拿到结果。
 *
 * 语义：
 * - [cancel] 幂等，可重复调用；
 * - 取消是**用户动作、不是失败**：客户端把结果折成本地码 [SourceSyncLocalReasons.CANCELLED]，
 *   由界面层咽掉（状态回 Idle、不留码）；若没经界面层，调用方仍能把这个码与 io-failed 分开；
 * - socket 的关闭与正常关闭路径互不排斥（finally 里的关闭是幂等的）。
 */
class SourceSyncCancellation {

    private val cancelled = AtomicBoolean(false)

    private val attached = AtomicReference<Closeable?>(null)

    /** 是否已经被取消（一旦为 true 不会再变回 false）。 */
    val isCancelled: Boolean
        get() = cancelled.get()

    /**
     * 请求取消：先把标志置位，再关掉当前挂着的连接（没有就什么都不关）。
     *
     * 先置位是刻意的：即使关连接与读取擦肩而过（读刚好返回），调用方看到的仍然是「已取消」。
     */
    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        attached.getAndSet(null)?.let { closeQuietly(it) }
    }

    /**
     * 把当前活跃连接挂上来（内部用，客户端在 socket 建好后调用）。
     *
     * 若挂之前就已经被取消，则立刻关掉它——避免「取消发生在 attach 之前」时连接活到读超时。
     */
    internal fun attach(closeable: Closeable) {
        if (cancelled.get()) {
            closeQuietly(closeable)
            return
        }
        attached.set(closeable)
        if (cancelled.get() && attached.compareAndSet(closeable, null)) {
            closeQuietly(closeable)
        }
    }

    /** 摘掉已结束的连接（内部用）。 */
    internal fun detach(closeable: Closeable) {
        attached.compareAndSet(closeable, null)
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: Exception) {
            // 关不掉也不影响结论：调用方已经决定这次接收作废。
        }
    }
}
