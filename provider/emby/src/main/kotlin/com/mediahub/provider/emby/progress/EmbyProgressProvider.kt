package com.mediahub.provider.emby.progress

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackProgress
import com.mediahub.provider.api.MediaProgressProvider
import com.mediahub.provider.emby.EmbyProviderSupport
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.session.EmbySessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Emby 进度上报（Phase 1H，Emby PROGRESS closeout）：独立实现当前 main 已有的
 * [MediaProgressProvider]——会话纪律镜像 1G-C 已封板的 JellyfinProgressProvider
 * （Mutex 原子状态机），wire 为 Emby SessionsController 三入口；
 * 错误映射走 [EmbyProviderSupport] 既有契约（与 library/search 同一来源）。
 *
 * - POST /Sessions/Playing（同条目首次上报，start）
 * - POST /Sessions/Playing/Progress（后续 / 关键事件 flush）
 * - POST /Sessions/Playing/Stopped（[reportFinalProgress]，退出权威进度写入者）
 *
 * - **生命周期状态机**（Mutex 串行，原子转移）：同条目首次 reportProgress →
 *   Playing（恰一次）+ Progress；后续 → Progress；**条目切换先为上一条目补发
 *   Stopped（best-effort，取消原样穿透）再开新会话**；[reportFinalProgress] 三态收口：
 *   无前导 Playing → 补 Playing + Stopped；同条目 active → 仅 Stopped；上一条目仍
 *   active 但 final 的是新条目 → Stopped(上一条目) + Playing + Stopped（旧会话不残留）——
 *   并关闭会话状态；final 后同条目再次上报 → 新 Playing（新会话）。
 *   禁止 Progress before Playing / 双重 Stopped / Stopped 后裸 Progress /
 *   上一条目状态泄漏到下一条目。
 * - 节流/触发节奏由 ProgressSyncCoordinator 控制（10s throttle + 关键事件 flush
 *   + final flush），本 provider 不建 timer。
 * - **PositionTicks 恒发（协议证据）**：ms→ticks（×10_000）；负值钳 0（服务端负值 400）；
 *   溢出钳 Long.MAX_VALUE；无 Int 转换。**不得省略**——Stopped 缺 PositionTicks 时
 *   服务端按"播放完成"处理（PlayCount++/Played=true/位置清零），退出刚打开的条目
 *   会被误标已看（Jellyfin 同源 SessionManager.OnPlaybackStopped 实证）。
 * - 错误语义：reportProgress 映射后经 [EmbyProviderSupport.mapError] 上抛
 *   （共享 ProgressSyncCoordinator 的 runCatching 保证不打断播放；401→AuthExpired
 *   交上层重新认证，**进度失败不清理 auth 会话**）；切换补发 Stopped 与 final Stopped
 *   best-effort；cancellation 一律原样透传。
 * - getContinueWatching/getResumePosition：v1 刻意空实现——本地"继续观看/续播"由
 *   Room 快照驱动（ProgressRepository，1B-2.1 真机已证）；远端列表聚合另行决策。
 */
class EmbyProgressProvider(
    private val server: MediaServer,
    private val api: EmbyApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: EmbySessionStore,
    private val logger: Logger,
) : MediaProgressProvider {

    /** 会话状态（provider 实例级；Factory 每 server 创建独立实例）；全部转移在 mutex 内。 */
    private val sessionMutex = Mutex()
    private var lastItemId: String? = null
    private var lastPositionTicks: Long? = null

    override suspend fun reportProgress(progress: PlaybackProgress) {
        val (token, userId) = EmbyProviderSupport.requireSession(server, tokenStore, sessionStore)
        try {
            sessionMutex.withLock {
                val positionTicks = toTicks(progress.positionMs)
                if (progress.itemId != lastItemId) {
                    // 条目切换：为上一条目补发 Stopped（final stop reporting），再开新会话。
                    // 显式 catch：cancellation 原样透传（不得吞进 best-effort 日志），
                    // 且取消后不得继续新会话的任何请求。
                    lastItemId?.let { previous ->
                        try {
                            api.playbackStopped(token, userId, previous, lastPositionTicks ?: 0L)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.w(LogTag.PROVIDER, "Emby Stopped 补发失败（best-effort） itemId=$previous", e)
                        }
                    }
                    api.playbackStart(token, userId, progress.itemId, positionTicks, playMethod(progress))
                    lastItemId = progress.itemId
                }
                api.playbackProgress(
                    token,
                    userId,
                    progress.itemId,
                    positionTicks,
                    progress.isPaused,
                    playMethod(progress),
                )
                lastPositionTicks = positionTicks
            }
        } catch (e: CancellationException) {
            // 取消红线：绝不折叠成业务异常（ADR-039 §10）
            throw e
        } catch (e: Exception) {
            throw EmbyProviderSupport.mapError(server.id, e)
        }
    }

    /**
     * 最终退出上报（shared finality hook，ADR-039）。
     * Emby 官方 Playback Check-ins lifecycle：Playing → Progress → Stopped 三方法
     * 共同维护用户活动和播放位置——**禁止发送裸 Stopped**（无前导 Playing 的
     * Stopped 在 Emby 协议上无 lifecycle 语义）。
     *
     * 三态收口（Mutex 内原子转移）：
     * - 无前导 Playing（如 <10s 短播放退出，coordinator sample 未触发）→
     *   补发 Playing + Stopped（保证 server session lifecycle 完整）；
     * - 同条目 active（lastItemId == final 条目）→ 仅 Stopped；
     * - 上一条目仍 active 但 final 的是新条目（切台后不足一个 sample 周期即退出）→
     *   先按 item-switch 同款规则 Stopped(上一条目，lastPositionTicks)，
     *   再 Playing + Stopped(final 条目)——旧 server session 不得残留。
     * Mutex 保证与在途 Progress 的先后：final 不得被迟到的 Progress 越过。
     * Stopped best-effort（退出路径不抛网络异常；短超时由协调器保证）；补发 Playing
     * 失败即终止 final 并将状态归零（防后续 reportProgress 发裸 Progress）；
     * cancellation 原样传播。非取消路径无论成败都关闭会话状态。
     */
    override suspend fun reportFinalProgress(progress: PlaybackProgress) {
        val (token, userId) = EmbyProviderSupport.requireSession(server, tokenStore, sessionStore)
        try {
            sessionMutex.withLock {
                val ticks = toTicks(progress.positionMs)
                val playMethod = playMethod(progress)
                val previousItemId = lastItemId
                if (previousItemId != null && previousItemId != progress.itemId) {
                    // 上一条目会话仍 active 但 final 的是新条目：先按 item-switch 同款
                    // 规则关旧会话（best-effort，位置取 lastPositionTicks），
                    // 否则旧 server session 残留。
                    try {
                        api.playbackStopped(token, userId, previousItemId, lastPositionTicks ?: 0L)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.w(LogTag.PROVIDER, "Emby final 前旧会话 Stopped 补发失败（best-effort） itemId=$previousItemId", e)
                    }
                }
                if (previousItemId != progress.itemId) {
                    // final 条目无前导 Playing → 补发（Playback Check-ins 禁止裸 Stopped）
                    try {
                        api.playbackStart(token, userId, progress.itemId, ticks, playMethod)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 新会话未开出（旧会话已尽力关闭）：状态必须归零，
                        // 防止后续 reportProgress 对该条目发裸 Progress
                        lastItemId = null
                        lastPositionTicks = null
                        throw e
                    }
                }
                try {
                    api.playbackStopped(token, userId, progress.itemId, ticks)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(LogTag.PROVIDER, "Emby 最终 Stopped 上报失败（best-effort） itemId=${progress.itemId}", e)
                }
                lastItemId = null
                lastPositionTicks = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EmbyProviderSupport.mapError(server.id, e)
        }
    }

    /** v1 刻意空实现：本地"继续观看"由 Room 快照驱动（1B-2.1 真机已证）。 */
    override suspend fun getContinueWatching(limit: Int): List<MediaItem> = emptyList()

    /** v1 刻意空实现：应用内续播位置由本地快照驱动；远端续播列表聚合另行决策。 */
    override suspend fun getResumePosition(itemId: String): Long? = null

    /** Emby PlayMethod wire 值来自真实 PlaybackSource.mode（有据才发，无 mode 则省略）。 */
    private fun playMethod(progress: PlaybackProgress): String? = when (progress.mode) {
        PlaybackMode.DIRECT_STREAM -> "DirectStream"
        PlaybackMode.DIRECT_PLAY -> "DirectPlay"
        PlaybackMode.TRANSCODE -> "Transcode"
        PlaybackMode.UNSUPPORTED -> null
        null -> null
    }

    /** ms → ticks（1 tick = 100ns，10_000 ticks = 1ms）：负值钳 0，溢出钳 Long 上限，无 Int 转换。 */
    private fun toTicks(positionMs: Long): Long {
        val ms = positionMs.coerceAtLeast(0L)
        return if (ms > MAX_POSITION_MS) Long.MAX_VALUE else ms * TICKS_PER_MILLIS
    }

    private companion object {
        const val TICKS_PER_MILLIS = 10_000L

        /** Long.MAX_VALUE / 10_000（≈29,247 年）；超过即钳，防乘法溢出为负。 */
        const val MAX_POSITION_MS = Long.MAX_VALUE / TICKS_PER_MILLIS
    }
}
