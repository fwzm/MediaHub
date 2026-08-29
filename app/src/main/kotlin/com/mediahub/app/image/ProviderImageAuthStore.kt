package com.mediahub.app.image

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderImageAuthContributor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 图片请求鉴权解析（Phase 1G-A，ADR-039 泛化 1B-2.3）：
 *
 * - origin（scheme+host+port）→ server 快照来自服务器表 Flow（增删改自动刷新）；
 *   **不再按 ServerType 过滤**——每个 server 经 [ProviderImageAuthContributor]（按类型分发，
 *   provider 各自实现）解析认证头，app 层不 import 任何具体 provider 模块；
 * - 认证头按请求经 contributor 惰性生成（runBlocking 于 OkHttp IO 线程；保证 re-login
 *   立即生效，不做缓存）——Emby 行为与 1B-2.3 逐字节等价；
 * - 非 known origin / 未登录 → null（请求原样放行，由服务端 401 或占位图兜底）；
 * - 跨 origin 重定向剥离由 core:network OriginScopedCredentialInterceptor 兜底（ADR-030）。
 */
@Singleton
class ProviderImageAuthStore @Inject constructor(
    serverStore: ServerStore,
    contributors: Set<@JvmSuppressWildcards ProviderImageAuthContributor>,
) {
    data class Origin(val scheme: String, val host: String, val port: Int)

    private data class ResolvedServer(val serverId: String, val contributor: ProviderImageAuthContributor)

    private val contributorByType: Map<ServerType, ProviderImageAuthContributor> =
        contributors.associateBy { it.serverType }

    @Volatile
    private var origins: Map<Origin, ResolvedServer> = emptyMap()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            serverStore.observeServers().collectLatest { servers ->
                origins = servers.toOriginMap()
            }
        }
    }

    /** 图片 URL → 鉴权头；非 known origin / 无匹配 contributor / 未登录返回 null。 */
    fun headersForUrl(url: HttpUrl): Map<String, String>? {
        val resolved = origins[Origin(url.scheme, url.host, url.port)] ?: return null
        return runBlocking { resolved.contributor.headersFor(resolved.serverId) }
    }

    private fun List<MediaServer>.toOriginMap(): Map<Origin, ResolvedServer> =
        asSequence().mapNotNull { server ->
            val contributor = contributorByType[server.type] ?: return@mapNotNull null
            runCatching { server.baseUrl.toHttpUrl() }.getOrNull()
                ?.let { Origin(it.scheme, it.host, it.port) to ResolvedServer(server.id, contributor) }
        }.toMap()
}
