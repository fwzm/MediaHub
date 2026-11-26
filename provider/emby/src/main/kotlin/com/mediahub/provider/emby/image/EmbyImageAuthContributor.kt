package com.mediahub.provider.emby.image

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.security.TokenStore
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderImageAuthContributor
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.session.EmbySessionStore
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Emby 图片鉴权贡献者（Phase 1G-A，ADR-039）：把 1B-2.3 起 app 层
 * EmbyImageAuthStore 的头生成逻辑**原样下沉**到 provider 层——app 不再为图片鉴权
 * import provider:emby，行为逐字节等价：
 * - `X-Emby-Token` + `X-Emby-Authorization`（UserId 惰性读取，re-login 立即生效）；
 * - 未登录 / 凭据缺失 → null（请求原样放行）。
 */
@Singleton
class EmbyImageAuthContributor @Inject constructor(
    private val tokenStore: TokenStore,
    sessionStorage: EmbySessionStore.Storage,
    identity: ClientIdentity,
) : ProviderImageAuthContributor {

    override val serverType: ServerType = ServerType.EMBY

    private val sessionStore = EmbySessionStore(sessionStorage)
    private val authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity)

    /**
     * Emby 认证 scope：baseUrl + `/emby`（与 EmbyEndpointResolver 同一幂等语义：
     * 已以 /emby 结尾不重复追加）。app 以此区分同 origin 下的 Emby/Jellyfin 路径域。
     */
    override fun authScopeUrl(baseUrl: String): String = buildString {
        append(baseUrl.trimEnd('/'))
        if (!endsWith("/emby")) append("/emby")
    }

    /**
     * 反代子路径兼容（用户条目 22）：baseUrl 形如 `https://h/custom` 时，服务端返回的
     * 图片绝对 URL 通常是 `https://h/custom/Items/...`（不带 `/emby`），只看
     * [authScopeUrl] 会匹配不到 scope → 图片 401 空白。
     *
     * 因此当且仅当 baseUrl **带非空 path** 时，额外登记 baseUrl 自身；
     * 裸 origin（path 为空）仍只有 `/emby` scope，避免把同 origin 的全部路径都归给本服务器
     * （跨服凭据隔离不变；同前缀多 serverId 仍由 app 层 fail closed）。
     */
    override fun authScopeUrls(baseUrl: String): List<String> {
        val trimmed = baseUrl.trim().trimEnd('/')
        val path = runCatching { trimmed.toHttpUrlOrNull()?.encodedPath }.getOrNull()
            .orEmpty().trim('/')
        return if (path.isEmpty()) listOf(authScopeUrl(trimmed))
        else listOf(authScopeUrl(trimmed), trimmed).distinct()
    }

    override suspend fun headersFor(serverId: String): Map<String, String>? {
        val token = tokenStore.readTokens(serverId)?.accessToken ?: return null
        val userId = sessionStore.read(serverId)?.userId
        return mapOf(
            TOKEN_HEADER to token,
            authHeaderBuilder.headerName() to authHeaderBuilder.build(userId),
        )
    }

    private companion object {
        const val TOKEN_HEADER = "X-Emby-Token"
    }
}
