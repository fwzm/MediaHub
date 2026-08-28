package com.mediahub.feature.search.engine

import com.mediahub.model.PageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 聚合搜索引擎（Phase 1C-1）：并发搜索所有具备 SEARCH 能力的数据源。
 *
 * - 并发上限 [maxConcurrency]（默认 4），单服务器超时 [perServerTimeoutMs]（默认 8s）。
 * - Partial success：任一服务器失败/超时只落入 [GlobalSearchState.errors]，
 *   其余服务器的命中照常展示，绝不整体 Error。
 * - 稳定排序：[GlobalSearchState.hits] 始终按 targets 传入顺序展开，
 *   与各服务器完成顺序无关（避免结果跳变）。
 * - 取消语义：消费方停止收集（ViewModel flatMapLatest 切换旧 query）即取消全部
 *   在途搜索；[CancellationException] 只传播、绝不折叠成业务 error。
 * - Flow 生命周期：全部服务器终态（成功/失败/超时）后自然 complete；
 *   空白 query 直接发空态并 complete，不发任何网络请求。
 * - 每次搜索只取第一页（snapshot 语义）；分页续拉是后续演进，不在首版。
 */
class GlobalSearchEngine(
    private val perServerTimeoutMs: Long = 8_000,
    private val maxConcurrency: Int = 4,
) {

    fun search(
        targets: List<SearchTarget>,
        query: String,
        pageSize: Int = 30,
    ): Flow<GlobalSearchState> = channelFlow {
        if (query.isBlank()) {
            send(GlobalSearchState.idle(query))
            return@channelFlow
        }

        val searching = targets.map { it.serverId }.toSet()
        val mutex = Mutex()
        var hitsByServer: Map<String, List<UnifiedSearchHit>> = emptyMap()
        var errors: MutableMap<String, String> = mutableMapOf()
        var completed: MutableSet<String> = mutableSetOf()

        /**
         * 快照构建与可变状态读取必须在同一 mutex 内（评审 P1-3）：
         * channelFlow 允许多生产者并发完成，锁外读 MutableMap/Set 可能撞上并发写。
         * send() 线程安全，留在锁外避免持锁发送。
         */
        suspend fun sendSnapshot() {
            val snapshot = mutex.withLock {
                val done = completed.toSet()
                // 稳定顺序：按 targets 传入顺序展开已完成服务器的命中
                val orderedHits = targets.flatMap { target -> hitsByServer[target.serverId].orEmpty() }
                GlobalSearchState(
                    query = query,
                    hits = orderedHits,
                    searchingServers = searching - done,
                    completedServers = done,
                    errors = errors.toMap(),
                )
            }
            send(snapshot)
        }

        send(GlobalSearchState(query = query, searchingServers = searching))

        coroutineScope {
            val semaphore = Semaphore(maxConcurrency)
            targets.forEach { target ->
                launch {
                    semaphore.withPermit {
                        try {
                            val page = withTimeout(perServerTimeoutMs) {
                                target.search(query, PageRequest(offset = 0, limit = pageSize))
                            }
                            mutex.withLock {
                                hitsByServer += target.serverId to page.items.map { hit ->
                                    UnifiedSearchHit(item = hit, serverName = target.serverName)
                                }
                                completed += target.serverId
                            }
                            sendSnapshot()
                        } catch (e: TimeoutCancellationException) {
                            mutex.withLock {
                                errors[target.serverId] = "搜索超时"
                                completed += target.serverId
                            }
                            sendSnapshot()
                        } catch (e: CancellationException) {
                            // 消费方取消（旧 query 被切换）：向上传播，禁止折叠成业务 error
                            throw e
                        } catch (e: Exception) {
                            mutex.withLock {
                                errors[target.serverId] = e.message?.takeIf(String::isNotBlank)
                                    ?: e::class.java.simpleName
                                completed += target.serverId
                            }
                            sendSnapshot()
                        }
                    }
                }
            }
        }
    }
}
