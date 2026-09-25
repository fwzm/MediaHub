package com.mediahub.provider.webdav

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
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
 * 池 16 + 并发调用者数（B 复现 21 > 16）；信号量准入后超出者在
 * `acquire()` 上**挂起排队**，不再到达线程池，故 CallerRuns 场景在准入
 * 不变量下**理论不可达**——[ThreadPoolExecutor.CallerRunsPolicy] 仅作为
 * 防御保留（若未来不变量被破坏，宁可就地执行也不静默丢弃任务）。
 *
 * 取消语义（排队者）：
 * - 在 `acquire()` 排队中被取消：抛 [CancellationException]、**未取得 permit**、
 *   任务不提交线程池、不发起网络（kotlinx `Semaphore` 的 FIFO 公平队列——
 *   公平可避免先来者饥饿；16 上限的探测/浏览流量下非公平的吞吐优势可忽略）。
 * - 已取得 permit 后被取消：`finally` 保证 permit 归还；若取消发生在任务
 *   真正运行前，任务首查 `cont.isActive` 立即让出（不发起网络）。permit
 *   归还可能先于该空跑任务执行——它不占网络在途名额，硬上界不受影响。
 *
 * 线程取舍（如实声明，A3-5 有界化）：阻塞 execute 占用一个桥接守护线程直至
 * 响应/取消，不同于 enqueue 的异步模型；WebDAV 探测/浏览流量小且有 callTimeout
 * 兜底。线程池**有界**（核心 0、上限 [MAX_BRIDGE_THREADS]、60s 空闲回收、
 * SynchronousQueue 直递）。
 */
private const val MAX_BRIDGE_THREADS = 16

private val BRIDGE_EXECUTOR: ExecutorService = ThreadPoolExecutor(
    0,
    MAX_BRIDGE_THREADS,
    60L, TimeUnit.SECONDS,
    SynchronousQueue(),
    { task -> Thread(task, "webdav-call-bridge").apply { isDaemon = true } },
    ThreadPoolExecutor.CallerRunsPolicy(),
)

/** 总在途硬闸门：任意时刻经本桥发起网络请求的调用数 ≤ [MAX_BRIDGE_THREADS]。 */
private val IN_FLIGHT_GATE = Semaphore(MAX_BRIDGE_THREADS)

internal suspend fun <T> Call.awaitCancellable(block: (Response) -> T): T {
    // 准入（A4）：先取在途名额，再提交线程池。排队中被取消 → CancellationException
    // 直接抛出（permit 未取得、任务不提交）；取得后任何退出路径经 finally 归还。
    IN_FLIGHT_GATE.acquire()
    try {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            BRIDGE_EXECUTOR.execute {
                // 排队后被取消（防御路径）：不发起网络，直接让出。
                if (!cont.isActive) return@execute
                try {
                    val response = execute()
                    var result: T? = null
                    var consumed = false
                    try {
                        response.use {
                            if (!cont.isActive) return@execute
                            result = block(it)
                            consumed = true
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return@execute
                    }
                    if (consumed && cont.isActive) cont.resume(result as T)
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                } catch (e: CancellationException) {
                    if (cont.isActive) cont.resumeWithException(e)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    } finally {
        IN_FLIGHT_GATE.release()
    }
}
