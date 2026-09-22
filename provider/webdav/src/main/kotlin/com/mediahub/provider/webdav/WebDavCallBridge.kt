package com.mediahub.provider.webdav

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
 * 线程取舍（如实声明，A3-5 有界化）：阻塞 execute 占用一个桥接守护线程直至
 * 响应/取消，不同于 enqueue 的异步模型；WebDAV 探测/浏览流量小且有 callTimeout
 * 兜底。线程池**有界**（核心 0、上限 [MAX_BRIDGE_THREADS]、60s 空闲回收、
 * SynchronousQueue 直递）——满载时新任务由提交线程就地执行（CallerRuns），
 * 不静默丢弃、不无界堆积；被取消但尚未开始 execute 的任务在真正运行时
 * 首查 `cont.isActive` 立即让出，不发起网络。
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

internal suspend fun <T> Call.awaitCancellable(block: (Response) -> T): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        BRIDGE_EXECUTOR.execute {
            // 排队后被取消（CallerRuns 就地执行路径）：不发起网络，直接让出。
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
