package com.mediahub.provider.jellyfin.progress

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiException
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.provider.api.MediaProgressProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.jellyfin.JellyfinProviderSupport
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Jellyfin 进度上报（Phase 1G-C，ADR-039 §9）：独立实现当前 main 已有的
 * [MediaProgressProvider]——POST /Sessions/Playing / /Sessions/Playing/Progress /
 * /Sessions/Playing/Stopped（官方 SessionsController 三入口）。
 *
 * - 节流/触发节奏完全由既有 ProgressSyncCoordinator 控制（UI fast / local snapshot /
 *   remote throttle / critical event flush / single final exit flush）；
 *   **本 provider 不建 timer**；final exit semantics unchanged（不改动共享层）。
 * - 会话语义（v1）：generic `reportProgress(progress)` 无 finality 信号，因此——
 *   同一条目的首次上报 → `Playing`（start）+ `Progress`；后续 → `Progress`；
 *   **条目切换时先为上一条目补发 `Stopped`（final stop reporting）再开新会话**。
 *   单次播放的最终退出以 final flush 的 `Progress` 收尾（Jellyfin 服务器按最后
 *   Progress 的 PositionTicks 记录续播位置，resume 语义完整）。若需严格
 *   "exit 必发 Stopped"，需给共享 MediaProgressProvider 增加可选 stop 钩子——
 *   属共享层变更，超出 C-slice（ADR-039 冻结：不改共享层 final exit 语义）。
 * - 位置单位换算：ms → ticks（×10_000）。
 * - 取消红线与错误 taxonomy 与其余能力一致。
 */
class JellyfinProgressProvider(
    private val server: MediaServer,
    private val api: JellyfinApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: JellyfinSessionStore,
    private val logger: Logger,
) : MediaProgressProvider {

    /** 会话状态（provider 实例级；Factory 每 server 创建独立实例，无并发共享）。 */
    private var lastItemId: String? = null
    private var lastPositionTicks: Long? = null

    override suspend fun reportProgress(progress: PlaybackProgress) {
        val (token, _) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        try {
            val positionTicks = progress.positionMs.takeIf { it > 0 }?.times(TICKS_PER_MILLIS)
            if (progress.itemId != lastItemId) {
                // 条目切换：为上一条目补发 Stopped（final stop reporting），再开新会话
                lastItemId?.let { previous ->
                    runCatching { api.playbackStopped(token, previous, lastPositionTicks) }
                        .onFailure {
                            logger.w(LogTag.UI, "Jellyfin Stopped 补发失败（best-effort）", it)
                        }
                }
                api.playbackStart(token, progress.itemId, positionTicks)
                lastItemId = progress.itemId
            }
            api.playbackProgress(token, progress.itemId, positionTicks, progress.isPaused)
            lastPositionTicks = positionTicks
        } catch (e: CancellationException) {
            // 取消红线：绝不折叠成业务异常（ADR-039 §10）
            throw e
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    /** 继续观看：GET /Items?Filters=IsResumable（官方 filter），Video 媒体类型。 */
    override suspend fun getContinueWatching(limit: Int): List<MediaItem> {
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val result = api.getResumableItems(token, userId, limit)
            result.items.mapNotNull { dto ->
                com.mediahub.provider.jellyfin.mapper.JellyfinItemMapper.map(dto, server.id)?.let { item ->
                    com.mediahub.provider.jellyfin.mapper.JellyfinImageMapper.enrich(
                        item, api, dto.imageTags, dto.backdropImageTags,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    /** 续播位置：条目 UserData.PlaybackPositionTicks → ms；无记录返回 null。 */
    override suspend fun getResumePosition(itemId: String): Long? {
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val dto = api.getItemDetail(token, userId, itemId)
            dto.userData?.playbackPositionTicks
                ?.takeIf { it > 0 }
                ?.div(TICKS_PER_MILLIS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    private companion object {
        const val TICKS_PER_MILLIS = 10_000L
    }
}
