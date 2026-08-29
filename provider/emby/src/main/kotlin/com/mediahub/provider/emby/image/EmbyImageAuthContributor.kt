package com.mediahub.provider.emby.image

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.security.TokenStore
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderImageAuthContributor
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.session.EmbySessionStore
import javax.inject.Inject
import javax.inject.Singleton

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
