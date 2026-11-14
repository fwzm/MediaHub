package com.mediahub.provider.jellyfin.image

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.security.TokenStore
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderImageAuthContributor
import com.mediahub.provider.jellyfin.api.JellyfinAuthorizationHeaderBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jellyfin 图片鉴权贡献者（Phase 1G-A，ADR-039）：
 * 标准 `Authorization: MediaBrowser … Token="…"` 单头注入（Token 永不进 URL）；
 * 未登录 → null（原样放行）。X-Emby-* 与 X-MediaBrowser-* legacy 头不使用。
 */
@Singleton
class JellyfinImageAuthContributor @Inject constructor(
    private val tokenStore: TokenStore,
    identity: ClientIdentity,
) : ProviderImageAuthContributor {

    override val serverType: ServerType = ServerType.JELLYFIN

    private val authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(identity)

    /** Jellyfin 认证 scope = baseUrl 原样（无协议前缀；反代子路径已含在用户 base 中，ADR-039）。 */
    override fun authScopeUrl(baseUrl: String): String = baseUrl.trimEnd('/')

    /**
     * 反代子路径兼容（用户条目 22）：Jellyfin 的协议前缀本就是 baseUrl，额外 scope 与之相同，
     * 这里显式收敛为唯一 scope；裸 origin 同样不产生根路径 scope（避免吞并同 origin 其它服务器）。
     */
    override fun authScopeUrls(baseUrl: String): List<String> = listOf(authScopeUrl(baseUrl))

    override suspend fun headersFor(serverId: String): Map<String, String>? {
        val token = tokenStore.readTokens(serverId)?.accessToken ?: return null
        return mapOf(authHeaderBuilder.headerName() to authHeaderBuilder.build(token))
    }
}
