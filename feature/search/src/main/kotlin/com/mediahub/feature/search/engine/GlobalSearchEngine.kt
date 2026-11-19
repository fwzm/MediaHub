package com.mediahub.feature.search.engine

import com.mediahub.model.PageRequest
import com.mediahub.provider.api.ProviderException
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
 * - 分页：首轮记录各服务器 nextOffset；[loadMore] 只续拉仍有下一页的服务器。
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
        var nextOffsets: MutableMap<String, Int> = mutableMapOf()
        var errors: MutableMap<String, String> = mutableMapOf()
        var completed: MutableSet<String> = mutableSetOf()

        suspend fun sendSnapshot() {
            mutex.withLock {
                val done = completed.toSet()
                val orderedHits = targets.flatMap { target -> hitsByServer[target.serverId].orEmpty() }
                send(
                    GlobalSearchState(
                        query = query,
                        hits = orderedHits,
                        searchingServers = searching - done,
                        completedServers = done,
                        errors = errors.toMap(),
                        nextOffsets = nextOffsets.toMap(),
                    )
                )
            }
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
                            val next = page.nextOffset?.takeIf {
                                page.hasMore && page.items.isNotEmpty() && it > 0
                            }
                            mutex.withLock {
                                hitsByServer += target.serverId to page.items.map { hit ->
                                    UnifiedSearchHit(item = hit, serverName = target.serverName)
                                }
                                if (next != null) {
                                    nextOffsets[target.serverId] = next
                                } else {
                                    nextOffsets.remove(target.serverId)
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
                            throw e
                        } catch (e: Exception) {
                            mutex.withLock {
                                errors[target.serverId] = when (e) {
                                    is ProviderException -> e.message?.takeIf(String::isNotBlank) ?: "搜索失败"
                                    else -> "搜索失败"
                                }
                                completed += target.serverId
                            }
                            sendSnapshot()
                        }
                    }
                }
            }
        }
    }

    /**
     * 续拉下一页：只请求 [current] 里记录了 nextOffset 的服务器；
     * 新命中按 targets 顺序追加并去重，失败时保留旧命中与旧 offset 供再次重试。
     */
    fun loadMore(
        targets: List<SearchTarget>,
        current: GlobalSearchState,
        pageSize: Int = 30,
    ): Flow<GlobalSearchState> = channelFlow {
        val targetsToLoad = targets.filter { current.nextOffsets.containsKey(it.serverId) }
        if (current.isSearching || current.nextOffsets.isEmpty() || targetsToLoad.isEmpty()) {
            send(current)
            return@channelFlow
        }

        val searching = targetsToLoad.map { it.serverId }.toSet()
        val remaining = searching.toMutableSet()
        val mutex = Mutex()
        val appendedByServer: MutableMap<String, List<UnifiedSearchHit>> = mutableMapOf()
        val nextOffsets = current.nextOffsets.toMutableMap()
        val errors = current.errors.toMutableMap()
        val completed = current.completedServers.toMutableSet()

        fun snapshot(): GlobalSearchState {
            val ordered = buildList {
                addAll(current.hits)
                val seen = current.hits.mapTo(mutableSetOf()) { it.item.serverId + "\u0000" + it.item.id }
                targets.forEach { target ->
                    appendedByServer[target.serverId].orEmpty().forEach { hit ->
                        val key = hit.item.serverId + "\u0000" + hit.item.id
                        if (seen.add(key)) add(hit)
                    }
                }
            }
            return GlobalSearchState(
                query = current.query,
                hits = ordered,
                searchingServers = remaining.toSet(),
                completedServers = completed.toSet(),
                errors = errors.toMap(),
                nextOffsets = nextOffsets.toMap(),
            )
        }

        suspend fun sendSnapshot() {
            mutex.withLock { send(snapshot()) }
        }

        send(snapshot())

        coroutineScope {
            val semaphore = Semaphore(maxConcurrency)
            targetsToLoad.forEach { target ->
                val offset = current.nextOffsets.getValue(target.serverId)
                launch {
                    semaphore.withPermit {
                        try {
                            val page = withTimeout(perServerTimeoutMs) {
                                target.search(current.query, PageRequest(offset = offset, limit = pageSize))
                            }
                            val next = page.nextOffset?.takeIf {
                                page.hasMore && page.items.isNotEmpty() && it > offset
                            }
                            mutex.withLock {
                                appendedByServer[target.serverId] = page.items.map { hit ->
                                    UnifiedSearchHit(item = hit, serverName = target.serverName)
                                }
                                if (next != null) {
                                    nextOffsets[target.serverId] = next
                                } else {
                                    nextOffsets.remove(target.serverId)
                                }
                                errors.remove(target.serverId)
                                remaining -= target.serverId
                                completed += target.serverId
                            }
                            sendSnapshot()
                        } catch (e: TimeoutCancellationException) {
                            mutex.withLock {
                                errors[target.serverId] = "搜索超时"
                                remaining -= target.serverId
                                completed += target.serverId
                            }
                            sendSnapshot()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            mutex.withLock {
                                errors[target.serverId] = when (e) {
                                    is ProviderException -> e.message?.takeIf(String::isNotBlank) ?: "搜索失败"
                                    else -> "搜索失败"
                                }
                                remaining -= target.serverId
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
