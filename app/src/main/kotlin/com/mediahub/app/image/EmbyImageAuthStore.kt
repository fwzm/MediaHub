package com.mediahub.app.image

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.session.EmbySessionStore
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
 * Emby 图片请求的 origin → 鉴权头解析（Phase 1B-2.3）。
 *
 * - origin（scheme+host+port）→ serverId 映射来自服务器表 Flow（增删改自动刷新）；
 * - Token/UserId 按请求惰性读取（runBlocking 于 OkHttp IO 线程，EncryptedPrefs
 *   首载后为内存读，量级微小；保证 re-login 换 Token 立即生效，不做缓存）；
 * - 非 Emby 服务器/未登录 → null（请求原样放行，由服务端 401 或占位图兜底）。
 */
@Singleton
class EmbyImageAuthStore @Inject constructor(
    serverStore: ServerStore,
    private val tokenStore: TokenStore,
    sessionStorage: EmbySessionStore.Storage,
    identity: ClientIdentity,
) {
    data class Origin(val scheme: String, val host: String, val port: Int)

    @Volatile
    private var origins: Map<Origin, String> = emptyMap()

    private val sessionStore = EmbySessionStore(sessionStorage)
    private val authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            serverStore.observeServers().collectLatest { servers ->
                origins = servers.filter { it.type == ServerType.EMBY }.toOriginMap()
            }
        }
    }

    /** 图片 URL → 鉴权头；非已知 Emby origin 返回 null。 */
    fun headersForUrl(url: HttpUrl): Map<String, String>? {
        val serverId = origins[Origin(url.scheme, url.host, url.port)] ?: return null
        return runBlocking {
            val token = tokenStore.readTokens(serverId)?.accessToken ?: return@runBlocking null
            val userId = sessionStore.read(serverId)?.userId
            mapOf(
                TOKEN_HEADER to token,
                authHeaderBuilder.headerName() to authHeaderBuilder.build(userId),
            )
        }
    }

    private fun List<MediaServer>.toOriginMap(): Map<Origin, String> =
        asSequence().mapNotNull { server ->
            runCatching { server.baseUrl.toHttpUrl() }.getOrNull()
                ?.let { Origin(it.scheme, it.host, it.port) to server.id }
        }.toMap()

    private companion object {
        const val TOKEN_HEADER = "X-Emby-Token"
    }
}
