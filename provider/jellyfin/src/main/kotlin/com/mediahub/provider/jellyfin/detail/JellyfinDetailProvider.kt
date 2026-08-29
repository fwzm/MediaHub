package com.mediahub.provider.jellyfin.detail

import com.mediahub.core.logging.Logger
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.jellyfin.JellyfinProviderSupport
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.mapper.JellyfinImageMapper
import com.mediahub.provider.jellyfin.mapper.JellyfinItemMapper
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore

/**
 * Jellyfin 条目详情（Phase 1G-B）：GET /Users/{userId}/Items/{itemId}（单条目全量端点）。
 *
 * - Movie/Series/Season/Episode 共用同一端点与映射；Series 的季/集继续走
 *   library 链（ParentId 浏览），MediaDetail 列表字段 v1 core parity 保持空。
 * - 映射含 People/Studios/ProviderIds/UserData（跨源身份必备）。
 */
class JellyfinDetailProvider(
    private val server: MediaServer,
    private val api: JellyfinApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: JellyfinSessionStore,
    private val logger: Logger,
) : MediaDetailProvider {

    override suspend fun getItemDetail(itemId: String): MediaDetail {
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val dto = api.getItemDetail(token, userId, itemId)
            val item = JellyfinItemMapper.map(dto, server.id)
                ?: throw com.mediahub.provider.api.ProviderException.Parse(
                    server.id,
                    IllegalStateException("详情响应缺少有效条目 Id"),
                )
            var enriched = JellyfinImageMapper.enrich(item, api, dto.imageTags, dto.backdropImageTags)
            // People 图片：mapper 保持纯函数，PrimaryImageTag 在 DTO 上，按 Id 回填 URL
            val personImageTags = dto.people.associate { it.id to it.primaryImageTag }
            enriched = enriched.copy(
                people = enriched.people.map { person ->
                    person.id?.let { pid ->
                        personImageTags[pid]?.let { tag ->
                            person.copy(imageUrl = JellyfinImageMapper.personImageUrl(api, pid, tag))
                        }
                    } ?: person
                },
            )
            MediaDetail(item = enriched)
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }
}
