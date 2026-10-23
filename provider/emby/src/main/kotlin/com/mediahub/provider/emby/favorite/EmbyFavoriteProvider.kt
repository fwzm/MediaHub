package com.mediahub.provider.emby.favorite

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.MediaFavoriteProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.EmbyProviderSupport
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.mapper.EmbyImageMapper
import com.mediahub.provider.emby.mapper.EmbyMediaItemMapper
import com.mediahub.provider.emby.session.EmbySessionStore
import kotlinx.coroutines.CancellationException

/**
 * Emby 收藏能力（task-22 / A1）。
 *
 * wire 契约（真机验证，Emby 4.10.2）：
 * - 写：`POST /Users/{userId}/FavoriteItems/{itemId}`（收藏）/ `DELETE`（取消），成功 200 空体；
 * - 读回：`GET /Users/{userId}/Items/{itemId}?Fields=UserData` → `UserData.IsFavorite`；
 * - **禁止** `/Users/{userId}/Favorites/{itemId}`：真服 404。
 *
 * 语义纪律（与 Windows 端 FavoriteService 必须一致）：
 * 1. **写后必须回读**，返回值 = 服务器真实状态。服务器"收下写请求但不改状态"时
 *    返回 false（不得谎报成功）。
 * 2. 回读缺 `UserData.IsFavorite` = 协议破损 → [ProviderException.Parse]，
 *    **绝不**降级为 false（否则取消收藏失败会被显示成成功）。
 * 3. 错误映射复用 [EmbyProviderSupport.mapError]（401→AuthExpired、
 *    404→NotFound、其余→Http），cancellation 原样透传。
 * 4. 无本地缓存/无 Room 表：收藏真源只在服务端（见 docs/android-favorites.md）。
 */
class EmbyFavoriteProvider(
    private val server: MediaServer,
    private val api: EmbyApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: EmbySessionStore,
    private val logger: Logger,
) : MediaFavoriteProvider {

    override suspend fun setFavorite(itemId: String, favorite: Boolean): Boolean {
        val (token, userId) = EmbyProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            api.setFavorite(token, userId, itemId, favorite)
            val state = readBack(token, userId, itemId)
            logger.d(
                LogTag.PROVIDER,
                "Emby 收藏写入完成 itemId=$itemId requested=$favorite readback=$state",
            )
            state
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EmbyProviderSupport.mapError(server.id, e)
        }
    }

    override suspend fun isFavorite(itemId: String): Boolean {
        val (token, userId) = EmbyProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            readBack(token, userId, itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EmbyProviderSupport.mapError(server.id, e)
        }
    }

    /**
     * 收藏列表（task-25 / A2）：服务端 `Filters=IsFavorite` 只是优化，
     * 客户端仍按 [MediaItem.isFavorite] 兜底过滤，避免服务器忽略筛选时
     * 把未收藏条目混进「我的收藏」。
     *
     * `limit <= 0` 直接返回空列表且不发 HTTP；上限 [MediaFavoriteProvider.MAX_FAVORITES_LIMIT]。
     */
    override suspend fun listFavorites(limit: Int): List<MediaItem> {
        if (limit <= 0) return emptyList()
        val capped = limit.coerceAtMost(MediaFavoriteProvider.MAX_FAVORITES_LIMIT)
        val (token, userId) = EmbyProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            api.getFavoriteItems(token, userId, capped).items
                .mapNotNull { dto ->
                    EmbyMediaItemMapper.map(dto, server.id)?.let { item ->
                        EmbyImageMapper.enrich(item, api, dto.imageTags, dto.backdropImageTags)
                    }
                }
                .filter { it.isFavorite }
                .distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EmbyProviderSupport.mapError(server.id, e)
        }
    }

    /** 回读并以 fail-closed 语义解读：缺失 IsFavorite 即 Parse（不是 false）。 */
    private suspend fun readBack(token: String, userId: String, itemId: String): Boolean =
        api.getFavoriteState(token, userId, itemId)
            ?: throw ProviderException.Parse(
                server.id,
                IllegalStateException("Emby 回读缺 UserData.IsFavorite itemId=$itemId"),
            )
}
