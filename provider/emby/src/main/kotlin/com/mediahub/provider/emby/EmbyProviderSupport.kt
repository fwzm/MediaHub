package com.mediahub.provider.emby

import com.mediahub.core.network.ApiException
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.session.EmbySessionStore
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Emby 能力 Provider 的公共支撑（Phase 1C-1 起由 library/search 共用）。
 *
 * 会话读取与错误映射只写一份：之前只有 [EmbyLibraryProvider] 用，
 * 1C-1 搜索接入后避免两份 DTO 各写一套会话/错误逻辑（见 mapper 单一来源的同一原则）。
 */
internal object EmbyProviderSupport {

    /** 读会话：缺 Token 或缺 userId 都抛 [ProviderException.AuthRequired]。 */
    suspend fun requireSession(
        server: MediaServer,
        tokenStore: TokenStore,
        sessionStore: EmbySessionStore,
    ): Pair<String, String> {
        val token = tokenStore.readTokens(server.id)?.accessToken
            ?: throw ProviderException.AuthRequired(server.id)
        val session = sessionStore.read(server.id)
            ?: throw ProviderException.AuthRequired(server.id)
        return token to session.userId
    }

    /**
     * 结构化错误映射：无 session、401、403、404、5xx、网络、解析分别表达（评审 #11）。
     *
     * 取消红线（Phase 1C-1 评审）：[CancellationException]（JVM 上即
     * kotlinx.coroutines.CancellationException 的实际类型）必须原样向上抛出，
     * 绝不能折叠成 ProviderException——旧 query 取消、flatMapLatest 切换、
     * 引擎取消在途请求都依赖它穿透 Provider 的 catch 边界。
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
