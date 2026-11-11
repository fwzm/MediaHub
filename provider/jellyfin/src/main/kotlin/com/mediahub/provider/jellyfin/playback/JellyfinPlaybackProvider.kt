package com.mediahub.provider.jellyfin.playback

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiException
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackSource
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.jellyfin.JellyfinProviderSupport
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Jellyfin 播放（Phase 1G-C，ADR-039：DIRECT STREAM ONLY / NO TRANSCODING）。
 * 流程语义镜像已封板的 EmbyPlaybackProvider，wire 为 Jellyfin 现代端点：
 *
 * - 仅视频型条目（MOVIE/EPISODE/VIDEO）；AUDIO/LIVE_TV/OTHER 明确 NotYetImplemented，0 HTTP；
 * - POST /Items/{itemId}/PlaybackInfo?UserId=… 协商（EnableDirectStream=true /
 *   EnableTranscoding=false），尊重 EnableDirectPlay，绝不申请转码；
 * - 默认服务器字幕阻止原字节播放时，允许一次源 ID 绑定的不处理服务器字幕协商；
 *   显式字幕语言偏好禁止这一重试，原媒体内嵌字幕不被删除；
 * - MediaSource 选择走 [JellyfinMediaSourceSelector]（SupportsDirectStream && 非 ISO）；
 * - 播放地址 = configured server origin 的 /Videos/{itemId}/stream.{container}?
 *   MediaSourceId=…&Static=true——**永远同源**，服务端凭据绝不发往第三方 origin；
 * - **URL 永不含 Token/api_key**：Token 只在 PlaybackSource.headers 的标准
 *   Authorization 头（由播放引擎 DataSource 注入）；
 * - RequiredHttpHeaders 并入播放头，但与鉴权头同名（含大小写差异）的源级键被剔除，
 *   权威 Authorization 后合并且获胜。
 */
class JellyfinPlaybackProvider(
    private val server: MediaServer,
    private val api: JellyfinApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: JellyfinSessionStore,
    @Suppress("UNUSED_PARAMETER") private val logger: Logger,
) : MediaPlaybackProvider {

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource {
        if (item.type !in DIRECT_STREAM_TYPES) {
            throw ProviderException.NotYetImplemented(
                server.id,
                if (item.type == com.mediahub.model.MediaType.AUDIO) "音频播放尚未接入" else "该媒体类型的播放尚未接入",
            )
        }
        // 无转码红线：任何显式要求转码/关闭直接流的选项组合直接拒绝（1G 不实现 TRANSCODE）。
        if (options.forceTranscode || !options.enableDirectStream) {
            throw ProviderException.NotYetImplemented(server.id, "当前媒体需要转码")
        }
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val startTimeTicks = options.startPositionMs?.times(TICKS_PER_MILLIS)
            val maxStreamingBitrate = options.maxBitrate
                ?.takeIf { it > 0 }
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
            val info = api.getPlaybackInfo(
                token = token,
                userId = userId,
                itemId = item.id,
                startTimeTicks = startTimeTicks,
                // Jellyfin MaxStreamingBitrate 为 int?：正数过滤 + Int.MAX_VALUE 安全收缩
                maxStreamingBitrate = maxStreamingBitrate,
                enableDirectPlay = options.enableDirectPlay,
            )
            var sources = info.mediaSources
            // 响应损坏（空 MediaSources）≠ 需要转码：明确报 Parse。
            if (sources.isEmpty()) throw ProviderException.Parse(server.id)
            // Jellyfin 12.1 disables ordinary HTTP remux and may require burn-in for its default
            // external subtitle when our profile declares no subtitle delivery capability.
            // One source-bound retry asks only for untouched static bytes. It neither requests
            // transcoding nor removes embedded subtitles from the media the native player receives.
            if (JellyfinMediaSourceSelector.selectDirectStream(sources) == null &&
                options.enableDirectPlay && options.preferredSubtitleLanguage.isNullOrBlank()
            ) {
                val candidate = JellyfinMediaSourceSelector.selectStaticNegotiationCandidate(sources)
                if (candidate != null) {
                    val retry = api.getPlaybackInfo(
                        token = token,
                        userId = userId,
                        itemId = item.id,
                        startTimeTicks = startTimeTicks,
                        maxStreamingBitrate = maxStreamingBitrate,
                        enableDirectPlay = true,
                        mediaSourceId = candidate.id,
                        subtitleStreamIndex = -1,
                    )
                    // A different source cannot satisfy a source-specific negotiation.
                    sources = retry.mediaSources.filter { it.id == candidate.id }
                    if (sources.isEmpty()) throw ProviderException.Parse(server.id)
                }
            }
            val source = JellyfinMediaSourceSelector.selectDirectStream(sources)
                ?: throw ProviderException.NotYetImplemented(server.id, "当前媒体需要转码")
            // Direct Stream 必备参数：缺了只能说明上游响应损坏，绝不生成残缺 URL。
            val mediaSourceId = source.id?.takeIf(String::isNotBlank)
                ?: throw ProviderException.Parse(server.id)
            val container = source.container?.takeIf(String::isNotBlank)
                ?: item.container?.takeIf(String::isNotBlank)
                ?: throw ProviderException.Parse(server.id)
            if (container.trim().equals("iso", ignoreCase = true)) {
                throw ProviderException.NotYetImplemented(server.id, "当前媒体需要转码")
            }
            val url = api.directStreamUrl(
                itemId = item.id,
                container = container,
                mediaSourceId = mediaSourceId,
            )
            val video = source.mediaStreams.firstOrNull { it.type?.lowercase() == "video" }
            val audio = source.mediaStreams.firstOrNull { it.type?.lowercase() == "audio" }
            val authenticatedHeaders = api.authenticatedHeaders(token)
            // 源级 RequiredHttpHeaders 并入，但与鉴权头同名（含大小写差异）的键剔除，
            // 权威 Authorization 后合并且获胜——绝不发送重复/被覆盖的凭据。
            val protectedSourceHeaders = source.requiredHttpHeaders.filterKeys { sourceHeader ->
                authenticatedHeaders.keys.none { authHeader ->
                    authHeader.equals(sourceHeader, ignoreCase = true)
                }
            }
            PlaybackSource(
                url = url,
                headers = protectedSourceHeaders + authenticatedHeaders,
                container = container,
                videoCodec = video?.codec,
                audioCodec = audio?.codec,
                bitrate = source.bitrate,
                width = video?.width,
                height = video?.height,
                // v1 core parity：Jellyfin VideoRange → HdrType 映射暂不实现（默认 NONE）
                durationMs = source.runTimeTicks?.div(TICKS_PER_MILLIS),
                mode = PlaybackMode.DIRECT_STREAM,
            )
        } catch (e: CancellationException) {
            // 取消红线：绝不折叠成业务异常（ADR-039 §10）
            throw e
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    private companion object {
        const val TICKS_PER_MILLIS = 10_000L

        /** 1G-C：本阶段只支持视频型播放（无转码 Direct Stream）。 */
        val DIRECT_STREAM_TYPES = setOf(MediaType.MOVIE, MediaType.EPISODE, MediaType.VIDEO)
    }
}
