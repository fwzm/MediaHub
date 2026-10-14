package com.mediahub.provider.webdav

import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response

/**
 * WebDAV 请求的可取消桥接（A2-2，取消语义对齐 #21 已验证的所有权契约）：
 *
 * - `cont.invokeOnCancellation { call.cancel() }`：协程取消 → **真实
 *   [Call.cancel]**，等待响应头与读体停滞都会被立即中止，socket 不等超时。
 * - [Call.execute] 在桥接专用守护线程上阻塞执行（不是调用方调度器）；
 *   cancel() 会把阻塞中的 execute 以 IOException 打断，由调用方
 *   `ensureActive()` 还原为 CancellationException。
 * - 响应所有权由桥接持有：[block] 在该线程内消费并 `use{}` 关闭响应，
 *   只有 `T` 跨 continuation 交付；恢复前取消时不消费已到达的响应且仍关闭。
 * - 调用链内抛出的任何异常（含拦截器的 CancellationException）原样传播，
 *   不折叠为普通失败。
 *
 * ## 总并发硬上限（A4，B 审查 REPRODUCED 修复）
 *
 * 桥入口先经 [IN_FLIGHT_GATE]（`Semaphore(MAX_BRIDGE_THREADS)`）准入：**总在途
 * 网络请求的硬上界 = [MAX_BRIDGE_THREADS] = 16，与并发调用者数量无关**。
 * 旧实现中池满后第 17 个任务由 CallerRuns 在提交线程就地执行，总在途 =
 * 池 16 + 并发调用者数（B 复现 21 > 16）。准入 permit 由 worker 持有直到
 * I/O 和响应关闭结束，包括调用方先取消的窗口。本池使用 16 条可超时回收
 * 线程与 16 格有界队列；前一任务释放 permit 到线程重新可用之间的小窗口可
 * 在队列内交接，绝不在提交协程的线程执行阻塞 I/O。
 *
 * 取消语义（排队者）：
 * - 在 `acquire()` 排队中被取消：抛 [CancellationException]、**未取得 permit**、
 *   任务不提交线程池、不发起网络（kotlinx `Semaphore` 的 FIFO 公平队列——
 *   公平可避免先来者饥饿；16 上限的探测/浏览流量下非公平的吞吐优势可忽略）。
 * - 已取得 permit 后被取消：调用方立即取消，permit 仍归 worker 所有，直到
 *   execute/读体/关闭全部退出才归还。否则旧 worker 尚未结束时新任务会进入
 *   CallerRuns，突破 16 上限并阻塞提交线程。任务运行前已取消则空跑后释放。
 *
 * 线程取舍（如实声明，A3-5 有界化）：阻塞 execute 占用一个桥接守护线程直至
 * 响应/取消，不同于 enqueue 的异步模型；WebDAV 探测/浏览流量小且有 callTimeout
 * 兜底。线程池**有界**（核心 0、上限 [MAX_BRIDGE_THREADS]、60s 空闲回收、
 * SynchronousQueue 直递）。
 */
private const val MAX_BRIDGE_THREADS = 16

private val BRIDGE_EXECUTOR: ExecutorService = ThreadPoolExecutor(
    MAX_BRIDGE_THREADS,
    MAX_BRIDGE_THREADS,
    60L, TimeUnit.SECONDS,
    ArrayBlockingQueue(MAX_BRIDGE_THREADS),
    { task -> Thread(task, "webdav-call-bridge").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
).apply { allowCoreThreadTimeOut(true) }

/** 总在途硬闸门：任意时刻经本桥发起网络请求的调用数 ≤ [MAX_BRIDGE_THREADS]。 */
private val IN_FLIGHT_GATE = Semaphore(MAX_BRIDGE_THREADS)

internal suspend fun <T> Call.awaitCancellable(block: (Response) -> T): T {
    // 准入（A4）：先取在途名额，再提交线程池。排队中被取消 → CancellationException
    // 直接抛出（permit 未取得、任务不提交）；取得后任何退出路径经 finally 归还。
    IN_FLIGHT_GATE.acquire()
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        // The worker owns admission, including after the caller is canceled. A canceled
        // continuation may finish before execute()/body consumption has left its thread.
        try {
            BRIDGE_EXECUTOR.execute {
                try {
                    if (!cont.isActive) return@execute
                    val result = execute().use { response ->
                        if (!cont.isActive) return@execute
                        block(response)
                    }
                    if (cont.isActive) cont.resume(result)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                } finally {
                    IN_FLIGHT_GATE.release()
                }
            }
        } catch (e: Throwable) {
            // Submission failed, so no worker can release its permit.
            IN_FLIGHT_GATE.release()
            if (cont.isActive) cont.resumeWithException(e)
        }
    }
}
