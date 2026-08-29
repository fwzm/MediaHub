package com.mediahub.feature.detail.source

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.model.CanonicalKey
import com.mediahub.model.ExternalIdProvider
import com.mediahub.model.ExternalIds
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaIdentityLookupProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CanonicalSourceResolver（1F C1 / ADR-038 冻结场景）：
 * 有界传递闭包 frontier / 查询含当前服务器 / occurrence 语义 / 硬边界 /
 * partial 降级 / 取消红线 / per-server 超时。
 */
class CanonicalSourceResolverTest {

    // ---- fakes ----

    private class FakeServerStore(private val servers: List<MediaServer>) : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = MutableStateFlow(servers)
        override suspend fun getServer(id: String): MediaServer? =
            servers.firstOrNull { it.id == id }
    }

    private class FakeRegistry(private val handles: Map<String, ProviderHandle>) :
        MediaProviderRegistry {
        override fun factoryFor(type: ServerType): MediaProviderFactory? = null
        override fun create(server: MediaServer): ProviderHandle? = handles[server.id]
        override val supportedTypes: Set<ServerType> get() = emptySet()
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
    }

    private fun fakeProvider(serverId: String) = object : MediaProvider {
        override val serverId: String = serverId
        override val type: ServerType = ServerType.EMBY
        override val displayName: String = serverId
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = "emby", serverType = ServerType.EMBY, displayName = "Emby",
            category = ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = setOf(ProviderCapability.IDENTITY_LOOKUP),
            authMethod = AuthMethod.NONE, status = ProviderStatus.STABLE,
        )
        override suspend fun testConnection() = ConnectionStatus(ok = true, message = "ok")
    }

    private class FakeIdentityLookup : MediaIdentityLookupProvider {
        var respond: (Set<CanonicalKey>) -> List<MediaItem> = { emptyList() }
        var failure: Exception? = null
        var delayMs: Long = 0
        val queries = mutableListOf<Set<CanonicalKey>>()
        val pages = mutableListOf<PageRequest>()

        override suspend fun findByCanonicalKeys(
            keys: Set<CanonicalKey>,
            page: PageRequest,
        ): PagedResult<MediaItem> {
            queries += keys
            pages += page
            failure?.let { throw it }
            if (delayMs > 0) delay(delayMs)
            return PagedResult(items = respond(keys))
        }
    }

    // ---- helpers ----

    private fun item(
        serverId: String,
        id: String,
        ids: ExternalIds?,
        type: MediaType = MediaType.MOVIE,
    ) = MediaItem(
        serverId = serverId, id = id, type = type, title = "Fargo",
        year = 2014, externalIds = ids,
    )

    private fun server(id: String, name: String) = MediaServer(
        id = id, name = name, type = ServerType.EMBY,
        baseUrl = "http://localhost", createdAtEpochMs = 0,
    )

    private fun handle(serverId: String, lookup: FakeIdentityLookup?) = ProviderHandle(
        provider = fakeProvider(serverId),
        identityLookup = lookup,
    )

    private fun resolver(
        vararg handles: Pair<String, ProviderHandle>,
    ): CanonicalSourceResolver {
        val servers = handles.map { (id, _) -> server(id, id.substringAfterLast('-').uppercase()) }
        return CanonicalSourceResolver(
            serverStore = FakeServerStore(servers),
            registry = FakeRegistry(handles.toMap()),
            logger = StdoutLogger(),
        )
    }

    // ---- 1：seed 无有效外部身份 → Idle，零 lookup ----

    @Test
    fun `seed without external ids resolves idle without any lookup`() = runTest {
        val lookupA = FakeIdentityLookup()
        val result = resolver("srv-a" to handle("srv-a", lookupA))
            .resolve(item("srv-a", "a1", null), activeServerId = "srv-a")

        assertEquals(SourceResolution.Idle, result)
        assertEquals(0, lookupA.queries.size)
    }

    // ---- 2：Episode seed → Idle（v1 只开放 Movie/Series selector） ----

    @Test
    fun `episode seed resolves idle even with external ids`() = runTest {
        val lookupA = FakeIdentityLookup()
        val result = resolver("srv-a" to handle("srv-a", lookupA))
            .resolve(
                item("srv-a", "e1", ExternalIds(tvdb = "ep-9"), type = MediaType.EPISODE),
                activeServerId = "srv-a",
            )

        assertEquals(SourceResolution.Idle, result)
        assertEquals(0, lookupA.queries.size)
    }

    // ---- 3：跨服务器 sibling 发现；查询含当前服务器自身；active 首位 ----

    @Test
    fun `discovers cross server sibling and queries current server too`() = runTest {
        val lookupA = FakeIdentityLookup() // 当前服务器自身也被查询（本例无副本）
        val lookupB = FakeIdentityLookup().apply {
            respond = { listOf(item("srv-b", "b1", ExternalIds(tmdb = "1"))) }
        }
        val result = resolver(
            "srv-a" to handle("srv-a", lookupA),
            "srv-b" to handle("srv-b", lookupB),
        ).resolve(
            seed = item("srv-a", "a1", ExternalIds(tmdb = "1")),
            activeServerId = "srv-a",
        ) as SourceResolution.Completed

        assertEquals(listOf("a1", "b1"), result.occurrences.map { it.item.id })
        assertEquals("A", result.occurrences[0].serverName)
        assertEquals("B", result.occurrences[1].serverName)
        assertTrue(result.occurrences[0].isActive)
        assertFalse(result.occurrences[1].isActive)
        assertEquals("srv-a", result.occurrences[0].serverId)
        assertEquals("srv-b", result.occurrences[1].serverId)
        assertFalse(result.truncated)
        // 当前服务器自身进入查询（ADR-038：同服另一条目可为 alias bridge）
        assertEquals(1, lookupA.queries.size)
        assertEquals(setOf(CanonicalKey(MediaType.MOVIE, ExternalIdProvider.TMDB, "1")), lookupA.queries.single())
        // 单页 contract：offset=0、limit=64
        val page = lookupB.pages.single()
        assertEquals(0, page.offset)
        assertEquals(64, page.limit)
    }

    // ---- 4：有界传递闭包——B 的 aliases 把 C 拉进 component（多轮 frontier） ----

    @Test
    fun `transitive closure discovers c through b aliases across rounds`() = runTest {
        // A(srv-a)={TMDB/1}；B(srv-b)={TMDB/1,IMDB/X}；C(srv-b)={IMDB/X}
        val lookupA = FakeIdentityLookup()
        val lookupB = FakeIdentityLookup().apply {
            respond = { keys ->
                when {
                    CanonicalKey(MediaType.MOVIE, ExternalIdProvider.TMDB, "1") in keys ->
                        listOf(item("srv-b", "b1", ExternalIds(tmdb = "1", imdb = "X")))
                    CanonicalKey(MediaType.MOVIE, ExternalIdProvider.IMDB, "X") in keys ->
                        listOf(item("srv-b", "c1", ExternalIds(imdb = "X")))
                    else -> emptyList()
                }
            }
        }
        val result = resolver(
            "srv-a" to handle("srv-a", lookupA),
            "srv-b" to handle("srv-b", lookupB),
        ).resolve(
            seed = item("srv-a", "a1", ExternalIds(tmdb = "1")),
            activeServerId = "srv-a",
        ) as SourceResolution.Completed

        // 单轮 direct lookup 只能发现 B；C 只能经 B 的 IMDB/X alias 在第二轮发现
        assertEquals(listOf("a1", "b1", "c1"), result.occurrences.map { it.item.id })
        assertEquals(2, lookupB.queries.size)
        assertFalse(result.truncated)
    }

    // ---- 5：同服务器重复条目 = 两条 occurrence；seed 重新发现被去重 ----

    @Test
    fun `same server duplicate copy becomes second occurrence with seed active`() = runTest {
        val lookupA = FakeIdentityLookup().apply {
            respond = {
                listOf(
                    item("srv-a", "a1", ExternalIds(tmdb = "1")), // seed 自身重新发现 → 去重
                    item("srv-a", "a1-dup", ExternalIds(tmdb = "1")), // 同服副本 → 独立 occurrence
                )
            }
        }
        val result = resolver("srv-a" to handle("srv-a", lookupA))
            .resolve(
                seed = item("srv-a", "a1", ExternalIds(tmdb = "1")),
                activeServerId = "srv-a",
            ) as SourceResolution.Completed

        assertEquals(listOf("a1", "a1-dup"), result.occurrences.map { it.item.id })
        assertEquals(listOf("srv-a", "srv-a"), result.occurrences.map { it.serverId })
        assertTrue(result.occurrences[0].isActive)
        assertFalse(result.occurrences[1].isActive)
    }

    // ---- 6：无 identityLookup 能力的服务器不参与 ----

    @Test
    fun `server without identity lookup capability never participates`() = runTest {
        val result = resolver(
            "srv-a" to handle("srv-a", FakeIdentityLookup()),
            "srv-b" to handle("srv-b", lookup = null), // browse 型源：无此能力
        ).resolve(
            seed = item("srv-a", "a1", ExternalIds(tmdb = "1")),
            activeServerId = "srv-a",
        ) as SourceResolution.Completed

        assertEquals(listOf("a1"), result.occurrences.map { it.item.id })
        assertFalse(result.truncated)
    }

    // ---- 7：单服务器失败 = partial，解析整体完成 ----

    @Test
    fun `per server failure degrades to partial without failing resolution`() = runTest {
        val broken = FakeIdentityLookup().apply { failure = RuntimeException("boom") }
        val result = resolver(
            "srv-a" to handle("srv-a", FakeIdentityLookup()),
            "srv-b" to handle("srv-b", broken),
        ).resolve(
            seed = item("srv-a", "a1", ExternalIds(tmdb = "1")),
            activeServerId = "srv-a",
        ) as SourceResolution.Completed

        assertEquals(listOf("a1"), result.occurrences.map { it.item.id })
        assertFalse(result.truncated)
    }

    // ---- 8：取消红线——lookup 的 CancellationException 必须穿透，不得折叠 ----

    @Test
    fun `lookup cancellation propagates instead of degrading`() = runTest {
        val cancelled = CancellationException("scope cancelled")
        val cancelling = FakeIdentityLookup().apply { failure = cancelled }
        try {
            resolver("srv-a" to handle("srv-a", cancelling))
                .resolve(
                    seed = item("srv-a", "a1", ExternalIds(tmdb = "1")),
                    activeServerId = "srv-a",
                )
            fail("必须抛 CancellationException")
        } catch (e: CancellationException) {
            assertTrue(
                "必须原样传播（实际：$e）",
                e === cancelled || e.cause === cancelled,
            )
        }
    }

    // ---- 9：per-server 超时（8s）→ 该服务器空贡献，解析整体完成 ----

    @Test
    fun `slow server times out and degrades to partial`() = runTest {
        val slow = FakeIdentityLookup().apply {
            delayMs = 20_000 // 虚拟时间：withTimeout(8s) 先触发
            respond = { listOf(item("srv-b", "b1", ExternalIds(tmdb = "1"))) }
        }
        val result = resolver(
            "srv-a" to handle("srv-a", FakeIdentityLookup()),
            "srv-b" to handle("srv-b", slow),
        ).resolve(
            seed = item("srv-a", "a1", ExternalIds(tmdb = "1")),
            activeServerId = "srv-a",
        ) as SourceResolution.Completed

        assertEquals(listOf("a1"), result.occurrences.map { it.item.id })
        assertFalse(result.truncated)
    }

    // ---- 10：maxOccurrences(64) 硬边界 → truncated ----

    @Test
    fun `occurrence cap truncates resolution`() = runTest {
        val flooding = FakeIdentityLookup().apply {
            respond = { (1..70).map { item("srv-b", "b$it", ExternalIds(tmdb = "1")) } }
        }
        val result = resolver(
            "srv-a" to handle("srv-a", FakeIdentityLookup()),
            "srv-b" to handle("srv-b", flooding),
        ).resolve(
            seed = item("srv-a", "a1", ExternalIds(tmdb = "1")),
            activeServerId = "srv-a",
        ) as SourceResolution.Completed

        assertEquals(64, result.occurrences.size)
        assertEquals("a1", result.occurrences.first().item.id)
        assertTrue(result.truncated)
    }

    // ---- 11：maxRounds(4) 硬边界 → 第 5 轮不再查询，标记 truncated ----

    @Test
    fun `rounds cap stops expansion and marks truncated`() = runTest {
        // 链式别名：查询 (provider, v) → 返回携带 {v, v+1} 双槽位的条目，每轮恰好新增一个 key
        val chain = FakeIdentityLookup().apply {
            respond = { keys ->
                val k = keys.single()
                val v = k.value.toInt()
                val next = (v + 1).toString()
                val ids = when (k.provider) {
                    ExternalIdProvider.TVDB -> ExternalIds(tvdb = v.toString(), imdb = next)
                    ExternalIdProvider.IMDB -> ExternalIds(imdb = v.toString(), tmdb = next)
                    ExternalIdProvider.TMDB -> ExternalIds(tmdb = v.toString(), imdb = next)
                }
                listOf(item("srv-b", "chain-$v", ids))
            }
        }
        val result = resolver(
            "srv-a" to handle("srv-a", FakeIdentityLookup()),
            "srv-b" to handle("srv-b", chain),
        ).resolve(
            seed = item("srv-a", "a1", ExternalIds(tvdb = "0")),
            activeServerId = "srv-a",
        ) as SourceResolution.Completed

        // 4 轮后仍有新 key（TVDB/0→1→2→3→4 链未走完）：不再扩张，truncated
        assertEquals(4, chain.queries.size)
        assertTrue(result.truncated)
        assertEquals(5, result.occurrences.size)
    }

    // ---- 12：maxCanonicalKeys(32) 硬边界 → 不再发起新查询轮 ----

    @Test
    fun `canonical keys cap truncates before next query round`() = runTest {
        val flooding = FakeIdentityLookup().apply {
            respond = { (1..40).map { item("srv-b", "b$it", ExternalIds(tmdb = "1", imdb = "tt$it")) } }
        }
        val result = resolver(
            "srv-a" to handle("srv-a", FakeIdentityLookup()),
            "srv-b" to handle("srv-b", flooding),
        ).resolve(
            seed = item("srv-a", "a1", ExternalIds(tmdb = "1")),
            activeServerId = "srv-a",
        ) as SourceResolution.Completed

        // round1 引入 40 个新 IMDB 键 → knownKeys=41 > 32：第二轮被边界拦截
        assertEquals(1, flooding.queries.size)
        assertTrue(result.truncated)
        assertEquals(41, result.occurrences.size)
    }
}
