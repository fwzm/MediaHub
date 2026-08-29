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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * Jellyfin 进度上报（Phase 1G-C，ADR-039 §9）：独立实现当前 main 已有的
 * [MediaProgressProvider]——POST /Sessions/Playing / /Sessions/Playing/Progress /
 * /Sessions/Playing/Stopped（官方 SessionsController 三入口）。
 *
 * - 节流/触发节奏完全由既有 ProgressSyncCoordinator 控制（UI fast / local snapshot /
 *   remote throttle / critical event flush / final exit flush）；
 *   **本 provider 不建 timer**；final exit semantics unchanged（不改动共享层）。
 * - **server session lifecycle 完整承载（ADR-039 review hardening）**：
 *   generic reportProgress 无 finality 信号，因此——同一条目首次上报 →
 *   `Playing`（start）+ `Progress`；后续 → `Progress`；**条目切换时先为上一条目
 *   补发 `Stopped`（final stop reporting）再开新会话**；最终退出经 override 的
 *   [reportFinalProgress]（shared finality hook）发送 `Stopped`（自带最终
 *   PositionTicks）——退出会话完整闭环，不以 final Progress 冒充。
 * - **并发纪律**：会话状态转移（Stopped → Playing → Progress → state update）
 *   由 Mutex 串行为**一个原子序列**——coordinator 的周期 remote sample 与
 *   critical-event flush 是不同 coroutine，禁止交错制造重复 Playing 或错序。
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

    /** 会话状态（provider 实例级；Factory 每 server 创建独立实例）；全部转移在 mutex 内。 */
    private val sessionMutex = Mutex()
    private var lastItemId: String? = null
    private var lastPositionTicks: Long? = null

    override suspend fun reportProgress(progress: PlaybackProgress) {
        val (token, _) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        try {
            sessionMutex.withLock {
                val positionTicks = progress.positionMs.takeIf { it > 0 }?.times(TICKS_PER_MILLIS)
                if (progress.itemId != lastItemId) {
                    // 条目切换：为上一条目补发 Stopped（final stop reporting），再开新会话。
                    // 显式 catch：cancellation 原样透传（不得吞进 best-effort 日志），
                    // 且取消后不得继续新会话的任何请求。
                    lastItemId?.let { previous ->
                        try {
                            api.playbackStopped(token, previous, lastPositionTicks)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.w(LogTag.UI, "Jellyfin Stopped 补发失败（best-effort）", e)
                        }
                    }
                    api.playbackStart(token, progress.itemId, positionTicks)
                    println("PROGRESS-DEBUG: start sent")
                    lastItemId = progress.itemId
                }
                println("PROGRESS-DEBUG: about to send progress")
                api.playbackProgress(token, progress.itemId, positionTicks, progress.isPaused)
                println("PROGRESS-DEBUG: progress sent")
                lastPositionTicks = positionTicks
            }
        } catch (e: CancellationException) {
            // 取消红线：绝不折叠成业务异常（ADR-039 §10）
            throw e
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    /**
     * 最终退出上报（shared finality hook override，ADR-039 review hardening）：
     * 只发 `/Sessions/Playing/Stopped`（自带最终 PositionTicks），**不以 final
     * Progress 冒充**；Mutex 内原子转移会话状态（关闭当前会话）。
     * cancellation 原样传播；普通失败 best-effort（退出不被慢网络阻塞的语义由
     * coordinator 的短超时保证）。
     */
    override suspend fun reportFinalProgress(progress: PlaybackProgress) {
        val (token, _) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        try {
            sessionMutex.withLock {
                try {
                    api.playbackStopped(
                        token,
                        progress.itemId,
                        progress.positionMs.takeIf { it > 0 }?.times(TICKS_PER_MILLIS),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(LogTag.UI, "Jellyfin 最终 Stopped 上报失败（best-effort）", e)
                }
                lastItemId = null
                lastPositionTicks = null
            }
        } catch (e: CancellationException) {
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
