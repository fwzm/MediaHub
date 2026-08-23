package com.mediahub.provider.emby.detail

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiException
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.mapper.EmbyDetailMapper
import com.mediahub.provider.emby.mapper.EmbyImageMapper
import com.mediahub.provider.emby.mapper.EmbyMetadataMapper
import com.mediahub.provider.emby.session.EmbySessionStore
import java.io.IOException
import kotlinx.serialization.SerializationException

/**
 * Emby 条目详情（Phase 1B-2）：GET /Users/{userId}/Items/{itemId}。
 *
 * 会话/错误映射与 EmbyLibraryProvider 同构（各能力类自包含，见 ADR-027）；
 * 认证生命周期/清理状态机不在这里复制（EmbyAuthProvider 的职责）。
 */
class EmbyDetailProvider(
    private val server: MediaServer,
    private val api: EmbyApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: EmbySessionStore,
    @Suppress("UNUSED_PARAMETER") private val logger: Logger,
) : MediaDetailProvider {

    override suspend fun getItemDetail(itemId: String): MediaDetail {
        val (token, userId) = requireSession()
        return try {
            val dto = api.getUserItem(token, userId, itemId)
            val detail = EmbyDetailMapper.mapDetail(dto, server.id)
                ?: throw ProviderException.Parse(server.id)
            // 详情页图片：item 上 enrich（detail DTO 现含 ImageTags/BackdropImageTags）
            val enrichedItem = EmbyImageMapper.enrich(detail.item, api, dto.imageTags, dto.backdropImageTags)
            // 演职人员 / 制作公司 / 标签映射（Phase 1B-3 Metadata Pipeline）
            detail.copy(
                item = enrichedItem.copy(
                    people = EmbyMetadataMapper.mapPeople(api, dto.people),
                    studios = EmbyMetadataMapper.mapStudios(dto.studios),
                    tags = EmbyMetadataMapper.mapTags(dto.tags),
                ),
            )
        } catch (e: Exception) {
            throw mapError(e)
        }
    }

    private fun mapError(e: Exception): ProviderException = when (e) {
        is ProviderException -> e
        is ApiException -> when (e.statusCode) {
            401 -> ProviderException.AuthExpired(server.id)
            404 -> ProviderException.NotFound(server.id, "条目")
            else -> ProviderException.Http(server.id, e.statusCode, e.url, e.method, e.requestId)
        }
        is SerializationException -> ProviderException.Parse(server.id, e)
        is IOException -> ProviderException.Network(server.id, e)
        else -> ProviderException.Unknown(server.id, e)
    }

    private suspend fun requireSession(): Pair<String, String> {
        val token = tokenStore.readTokens(server.id)?.accessToken
            ?: throw ProviderException.AuthRequired(server.id)
        val session = sessionStore.read(server.id)
            ?: throw ProviderException.AuthRequired(server.id)
        return token to session.userId
    }
}
