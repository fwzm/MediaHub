package com.mediahub.provider.jellyfin.favorite

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.MediaFavoriteProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.jellyfin.JellyfinProviderSupport
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.mapper.JellyfinImageMapper
import com.mediahub.provider.jellyfin.mapper.JellyfinItemMapper
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import kotlinx.coroutines.CancellationException

/**
 * Jellyfin 收藏能力（task-22 / A1）。
 *
 * wire 契约（真机验证，Jellyfin 12.2，与 Emby 同名同义）：
 * - 写：`POST /Users/{userId}/FavoriteItems/{itemId}` / `DELETE`，成功 200 空体；
 * - 读回：`GET /Users/{userId}/Items/{itemId}?Fields=UserData` → `UserData.IsFavorite`；
 * - **禁止** `/Users/{userId}/Favorites/{itemId}`：真服 404。
 *
 * 路由说明（收藏回读的**既有选择**，不是 ADR-039 的现代路由，仅限本能力）：
 * Jellyfin 浏览/详情一律走现代路由（`/Items?UserId=…`、`/Items/{id}?UserId=…`，ADR-039）；
 * 收藏写端点没有现代等价物（`UsersController` 只有 `/Users/{userId}/FavoriteItems/{itemId}`），
 * 回读刻意与写端点同属 `/Users/{userId}/…` 家族（两端均以此路径做过真服实测），
 * 保证"写/读同一个用户作用域"；**不得**把它当作现代路由的替代方案。
 * 该例外只出现在本文件与 [JellyfinApiClient.setFavorite]/[JellyfinApiClient.getFavoriteState]，
 * 不外溢到 library/detail/search。
 *
 * 语义纪律同 Emby（交叉引用 [com.mediahub.provider.emby.favorite.EmbyFavoriteProvider] 文档）：
 * 写后必须回读；回读缺 `IsFavorite` → [ProviderException.Parse]，绝不降级 false。
 */
class JellyfinFavoriteProvider(
    private val server: MediaServer,
    private val api: JellyfinApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: JellyfinSessionStore,
    private val logger: Logger,
) : MediaFavoriteProvider {

    override suspend fun setFavorite(itemId: String, favorite: Boolean): Boolean {
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            api.setFavorite(token, userId, itemId, favorite)
            val state = readBack(token, userId, itemId)
            logger.d(
                LogTag.PROVIDER,
                "Jellyfin 收藏写入完成 itemId=$itemId requested=$favorite readback=$state",
            )
            state
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    override suspend fun isFavorite(itemId: String): Boolean {
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            readBack(token, userId, itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    /**
     * 收藏列表（task-25 / A2）：与写/读回不同，列表读取走 ADR-039 现代路由
     * `/Items?UserId=…&Filters=IsFavorite`；客户端仍按 [MediaItem.isFavorite] 兜底过滤。
     * `limit <= 0` 直接返回空列表且不发 HTTP。
     */
    override suspend fun listFavorites(limit: Int): List<MediaItem> {
        if (limit <= 0) return emptyList()
        val capped = limit.coerceAtMost(MediaFavoriteProvider.MAX_FAVORITES_LIMIT)
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            api.getFavoriteItems(token, userId, capped).items
                .mapNotNull { dto ->
                    JellyfinItemMapper.map(dto, server.id)?.let { item ->
                        JellyfinImageMapper.enrich(item, api, dto.imageTags, dto.backdropImageTags)
                    }
                }
                .filter { it.isFavorite }
                .distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    private suspend fun readBack(token: String, userId: String, itemId: String): Boolean =
        api.getFavoriteState(token, userId, itemId)
            ?: throw ProviderException.Parse(
                server.id,
                IllegalStateException("Jellyfin 回读缺 UserData.IsFavorite itemId=$itemId"),
            )
}
