package com.mediahub.provider.jellyfin

import com.mediahub.core.network.ApiException
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Jellyfin 能力 Provider 的公共支撑（Phase 1G-B；结构镜像 EmbyProviderSupport，
 * 会话读取与错误映射只写一份）。
 */
internal object JellyfinProviderSupport {

    /** 读会话：缺 Token 或缺 userId 都抛 [ProviderException.AuthRequired]。 */
    suspend fun requireSession(
        server: MediaServer,
        tokenStore: TokenStore,
        sessionStore: JellyfinSessionStore,
    ): Pair<String, String> {
        val token = tokenStore.readTokens(server.id)?.accessToken
            ?: throw ProviderException.AuthRequired(server.id)
        val session = sessionStore.read(server.id)
            ?: throw ProviderException.AuthRequired(server.id)
        return token to session.userId
    }

    /**
     * 结构化错误映射（与 EmbyProviderSupport 同一 taxonomy，ADR-039 §10）：
     * 401→AuthExpired、404→NotFound、5xx/其余 HTTP→Http、解析→Parse、网络→Network、
     * 其余→Unknown。取消红线：[CancellationException] 必须原样向上抛出，绝不折叠。
     */
    fun mapError(serverId: String, e: Exception): ProviderException {
        if (e is CancellationException) throw e
        return when (e) {
            is ProviderException -> e
            is ApiException -> when (e.statusCode) {
                401 -> ProviderException.AuthExpired(serverId)
                404 -> ProviderException.NotFound(serverId, "媒体库或条目")
                else -> ProviderException.Http(serverId, e.statusCode, e.url, e.method, e.requestId)
            }

            is SerializationException -> ProviderException.Parse(serverId, e)
            is IOException -> ProviderException.Network(serverId, e)
            else -> ProviderException.Unknown(serverId, e)
        }
    }
}
