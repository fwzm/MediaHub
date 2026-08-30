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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 图片请求鉴权解析（Phase 1G-A，ADR-039 泛化 1B-2.3；review hardening 后为
 * **auth-scope 归属**而非 origin 归属）：
 *
 * - 每个 server 经 [ProviderImageAuthContributor.authScopeUrl] 自述认证 scope；
 *   app 维护 **scope（origin + path 前缀）→ server** 快照（服务器表 Flow 增删改自动刷新）；
 * - 请求按 **同 origin + path-segment boundary 最长前缀** 匹配 scope——
 *   同 origin 多服务器（Emby `https://h/emby` + Jellyfin `https://h/jellyfin`）不串凭据；
 * - **同 scope 多 serverId → fail closed**（返回 null，绝不随机归属凭据）；
 * - 认证头按请求经 contributor 惰性生成（runBlocking 于 OkHttp IO 线程；保证 re-login
 *   立即生效，不做缓存）——Emby 行为与 1B-2.3 逐字节等价；
 * - 非 known scope / 未登录 → null（请求原样放行，由服务端 401 或占位图兜底）；
 * - 跨 origin 重定向剥离由 core:network OriginScopedCredentialInterceptor 兜底（ADR-030）。
 */
@Singleton
class ProviderImageAuthStore @Inject constructor(
    serverStore: ServerStore,
    contributors: Set<@JvmSuppressWildcards ProviderImageAuthContributor>,
) {
    data class Origin(val scheme: String, val host: String, val port: Int)

    private data class AuthScope(
        val origin: Origin,
        val scopeSegments: List<String>,
        val serverId: String,
        val contributor: ProviderImageAuthContributor,
    )

    private val contributorByType: Map<ServerType, ProviderImageAuthContributor> =
        contributors.associateBy { it.serverType }

    @Volatile
    private var authScopes: List<AuthScope> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            serverStore.observeServers().collectLatest { servers ->
                onServersChanged(servers)
            }
        }
    }

    /** 服务器表变更 → 重建 scope 快照（internal 供 JVM 单测确定性驱动）。 */
    internal fun onServersChanged(servers: List<MediaServer>) {
        authScopes = servers.toAuthScopes()
    }

    /** 图片 URL → 鉴权头；非 known scope / 归属歧义 / 未登录返回 null（fail closed）。 */
    fun headersForUrl(url: HttpUrl): Map<String, String>? {
        val origin = Origin(url.scheme, url.host, url.port)
        val requestSegments = url.encodedPath.split('/').filter { it.isNotEmpty() }

        val matched = authScopes
            .filter { it.origin == origin }
            .filter { isPathPrefix(requestSegments, it.scopeSegments) }
        if (matched.isEmpty()) return null

        // 最长前缀（path-segment 数）优先
        val bestLength = matched.maxOf { it.scopeSegments.size }
        val best = matched.filter { it.scopeSegments.size == bestLength }
        // 同 scope 多 serverId：URL 无法安全归属 → fail closed
        if (best.map { it.serverId }.distinct().size > 1) return null
        val resolved = best.first()
        return runBlocking { resolved.contributor.headersFor(resolved.serverId) }
    }

    private fun List<MediaServer>.toAuthScopes(): List<AuthScope> =
        asSequence().mapNotNull { server ->
            val contributor = contributorByType[server.type] ?: return@mapNotNull null
            val scopeUrl = runCatching { contributor.authScopeUrl(server.baseUrl).toHttpUrl() }
                .getOrNull() ?: return@mapNotNull null
            AuthScope(
                origin = Origin(scopeUrl.scheme, scopeUrl.host, scopeUrl.port),
                scopeSegments = scopeUrl.encodedPath.split('/').filter { it.isNotEmpty() },
                serverId = server.id,
                contributor = contributor,
            )
        }.toList()

    private fun isPathPrefix(requestSegments: List<String>, scopeSegments: List<String>): Boolean {
        if (scopeSegments.isEmpty()) return true
        if (requestSegments.size < scopeSegments.size) return false
        return requestSegments.subList(0, scopeSegments.size) == scopeSegments
    }

}
