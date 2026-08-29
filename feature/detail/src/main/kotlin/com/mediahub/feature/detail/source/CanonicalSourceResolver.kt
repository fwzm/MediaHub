package com.mediahub.feature.detail.source

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.CanonicalIdentityGraph
import com.mediahub.model.CanonicalKey
import com.mediahub.model.CanonicalKeyPolicy
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.provider.api.MediaIdentityLookupProvider
import com.mediahub.provider.api.MediaProviderRegistry
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 一个可播放来源 = 一条 occurrence（ADR-038）：语义单位是条目而非服务器——
 * 同一服务器存在多个 canonical 相同的独立条目时各自成一条，不静默去重。
 * [isActive] = 当前 route 的 (serverId, itemId)（active 完全 args-driven）。
 */
data class CanonicalSourceOccurrence(
    val serverId: String,
    val serverName: String,
    val item: MediaItem,
    val isActive: Boolean,
)

/** 解析产出；进行中/空闲的 UI 状态由 DetailViewModel.sourceState 表达。 */
sealed interface SourceResolution {
    /** seed 无有效外部身份，或类型不在 v1 范围（Movie/Series）——不发起任何 lookup。 */
    data object Idle : SourceResolution

    data class Completed(
        val occurrences: List<CanonicalSourceOccurrence>,
        /** true = 触达轮次/键数/条目数任一硬边界，结果为 partial；禁止补齐推测。 */
        val truncated: Boolean,
    ) : SourceResolution
}

/**
 * CanonicalSourceResolver（Phase 1F C1，ADR-038）：
 * Detail 进入后按需解析同作品全部来源——与 SearchAggregator 共享
 * CanonicalIdentityGraph 的 connected-component 语义，不依赖 SearchTerm、
 * title/year 或导航上下文；occurrences 不进 navigation args。
 *
 * 算法 = **有界传递闭包**（frontier 扩张，非单轮 direct lookup）：
 * 1. seed 当前 item 的 CanonicalKeyPolicy.keys 为初始 knownKeys；
 * 2. 每轮向**全部 identity-capable 服务器（含当前服务器自身**——同服务器另一条目
 *    可为 alias bridge）查询尚未查询过的新 keys（AnyProviderIdEquals 类精确查找）；
 * 3. 返回条目本地按 CanonicalKey 复核（须与 knownKeys 相交），新条目带来新 aliases
 *    扩张 frontier；直到无新 key 或触达硬边界。
 * 4. 终态用 CanonicalIdentityGraph.components 重算连通分量，只保留 seed 所在
 *    component 的成员（与搜索聚合同一实现，禁止语义漂移）。
 *
 * 硬边界 v1（ADR-038 冻结）：concurrency ≤ 4；per-server timeout = 8s；
 * maxRounds = 4；maxCanonicalKeys = 32（轮次边界检查，超出不再发起新查询轮）；
 * maxOccurrences = 64。达上限返回 truncated，不做 title/year fallback。
 * 评审 P2-1：单页 lookup 返回 hasMore=true（服务器尚有后续页，可能含 alias bridge）
 * 同样只标 truncated（v1 不续拉分页），绝不把 partial 当 complete 声称。
 *
 * 失败语义：单服务器 lookup 失败/超时 = 该服务器本轮贡献为空（partial），
 * 绝不影响解析整体完成；[CancellationException] 必须穿透（取消红线，
 * 与 EmbyProviderSupport.mapError 同一纪律）。运行在调用方上下文
 * （dispatcher-neutral），route 销毁/切换由 ViewModel 作用域取消传导。
 */
class CanonicalSourceResolver @Inject constructor(
    private val serverStore: ServerStore,
    private val registry: MediaProviderRegistry,
    private val logger: Logger,
) {

    suspend fun resolve(seed: MediaItem, activeServerId: String): SourceResolution {
        val seedKeys = CanonicalKeyPolicy.keys(seed.type, seed.externalIds)
        if (seedKeys.isEmpty()) return SourceResolution.Idle
        // 1F feature 层只对 Movie/Series 开放（Episode selector 不展示，ADR-038）
        if (seed.type != MediaType.MOVIE && seed.type != MediaType.SERIES) {
            return SourceResolution.Idle
        }

        val targets = lookupTargets()
        // seed 占据首位的稳定顺序：active → 服务器配置序内按发现序
        val found = mutableListOf(
            FoundSource(activeServerId, activeOccurrenceName(targets, activeServerId), seed),
        )
        val occurrenceKeys = mutableSetOf(SeedKey(activeServerId, seed.id))
        val knownKeys = seedKeys.toMutableSet()
        val queriedKeys = mutableSetOf<CanonicalKey>()
        var truncated = false
        var incompletePagination = false

        var rounds = 0
        while (true) {
            val newKeys = knownKeys - queriedKeys
            if (newKeys.isEmpty()) break
            if (rounds >= MAX_ROUNDS || knownKeys.size > MAX_CANONICAL_KEYS) {
                truncated = true
                break
            }
            rounds++
            queriedKeys.addAll(newKeys)

            val perServer = lookupAll(targets, newKeys)
            // 评审 P2-1：任一服务器报 hasMore=true = 发现不完整（后续页可能含 alias
            // bridge）。只标记不中止——继续用已知 keys 在其余服务器上扩分量仍有价值。
            if (perServer.any { it.hasMore }) incompletePagination = true
            var mutated = false
            outer@ for (server in perServer) {
                for (item in server.items) {
                    val itemKeys = CanonicalKeyPolicy.keys(item.type, item.externalIds)
                    // 本地 CanonicalKey 复核：必须与已知键相交才算同 component 候选
                    if (itemKeys.none(knownKeys::contains)) continue
                    val key = SeedKey(server.serverId, item.id)
                    if (occurrenceKeys.contains(key)) continue
                    if (found.size >= MAX_OCCURRENCES) {
                        truncated = true
                        break@outer
                    }
                    occurrenceKeys += key
                    found += FoundSource(server.serverId, server.serverName, item)
                    knownKeys.addAll(itemKeys)
                    mutated = true
                }
            }
            if (truncated) break
            if (!mutated) break
        }

        // 终态分量重算（与 SearchAggregator 同一实现）：只保留 seed 所在 component
        val keySets = found.map { CanonicalKeyPolicy.keys(it.item.type, it.item.externalIds) }
        val seedComponent = CanonicalIdentityGraph.components(keySets)
            .firstOrNull { 0 in it.indices }
        val occurrences = (seedComponent?.indices ?: listOf(0)).map { idx ->
            val source = found[idx]
            CanonicalSourceOccurrence(
                serverId = source.serverId,
                serverName = source.serverName,
                item = source.item,
                isActive = idx == 0,
            )
        }
        return SourceResolution.Completed(
            occurrences = occurrences,
            truncated = truncated || incompletePagination,
        )
    }

    // ---- 内部结构 ----

    private data class SeedKey(val serverId: String, val itemId: String)

    /** 发现序条目：serverId 独立携带（occurrence 语义单位是条目，非服务器）。 */
    private data class FoundSource(
        val serverId: String,
        val serverName: String,
        val item: MediaItem,
    )

    private data class LookupTarget(
        val serverId: String,
        val serverName: String,
        val lookup: MediaIdentityLookupProvider,
    )

    private data class ServerLookup(
        val serverId: String,
        val serverName: String,
        val items: List<MediaItem>,
        val hasMore: Boolean,
    )

    /**
     * 全部 identity-capable 服务器目标（**含当前服务器**，ADR-038）；
     * Handle 创建失败按无能力处理，不拖垮其它服务器（与搜索同一纪律）。
     */
    private suspend fun lookupTargets(): List<LookupTarget> =
        serverStore.observeServers().first().mapNotNull { server ->
            val handle = runCatching { registry.create(server) }
                .onFailure { logger.w(LogTag.UI, "创建 ProviderHandle 失败 server=${server.name}", it) }
                .getOrNull()
            val lookup = handle?.identityLookup ?: return@mapNotNull null
            LookupTarget(server.id, server.name, lookup)
        }

    private fun activeOccurrenceName(targets: List<LookupTarget>, activeServerId: String): String =
        targets.firstOrNull { it.serverId == activeServerId }?.serverName ?: activeServerId

    /** 一轮查询：并发 ≤ 4、per-server timeout 8s；单服务器失败降级为空贡献。 */
    private suspend fun lookupAll(
        targets: List<LookupTarget>,
        keys: Set<CanonicalKey>,
    ): List<ServerLookup> = coroutineScope {
        val semaphore = Semaphore(LOOKUP_CONCURRENCY)
        targets.map { target ->
            async {
                semaphore.withPermit {
                    try {
                        withTimeout(PER_SERVER_TIMEOUT_MS) {
                            val result = target.lookup.findByCanonicalKeys(
                                keys,
                                PageRequest(offset = 0, limit = LOOKUP_PAGE_LIMIT),
                            )
                            ServerLookup(target.serverId, target.serverName, result.items, result.hasMore)
                        }
                    } catch (e: TimeoutCancellationException) {
                        // per-server 超时 = 该服务器本轮空贡献（partial），不是整体取消
                        logger.w(LogTag.UI, "identity lookup 超时 server=${target.serverName}", e)
                        null
                    } catch (e: CancellationException) {
                        // 真取消（scope/父 job）必须穿透，绝不折叠（取消红线）
                        throw e
                    } catch (e: Exception) {
                        logger.w(
                            LogTag.UI,
                            "identity lookup 部分失败 server=${target.serverName}",
                            e,
                        )
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private companion object {
        /** ADR-038 冻结硬边界 v1。 */
        const val MAX_ROUNDS = 4
        const val MAX_CANONICAL_KEYS = 32
        const val MAX_OCCURRENCES = 64
        const val LOOKUP_CONCURRENCY = 4
        const val PER_SERVER_TIMEOUT_MS = 8_000L

        /** 单页拉取上限与 maxOccurrences 对齐；续拉分页非 v1 范围，
         *  服务器报 hasMore=true 时结果标 truncated（评审 P2-1）。 */
        const val LOOKUP_PAGE_LIMIT = 64
    }
}
