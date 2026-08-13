package com.mediahub.provider.emby.playback

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
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.mapper.EmbyDetailMapper
import com.mediahub.provider.emby.session.EmbySessionStore
import java.io.IOException
import kotlinx.serialization.SerializationException

/**
 * Emby 播放（Phase 1B-2.1 FINAL HARDENING，无转码 Direct Stream）。
 *
 * 红线（任务书 + ADR-026）：
 * - 仅视频型条目（MOVIE/EPISODE/VIDEO）进入播放协议；AUDIO/LIVE_TV/OTHER 明确拒绝，
 *   绝不构造 /Videos/... 音频播放地址；
 * - PlaybackInfo 走官方 POST 协商，固定 EnableTranscoding=false / EnableDirectStream=true，
 *   只询问服务端能否 Direct Stream；绝不申请转码会话；
 * - MediaSourceId / PlaySessionId / container 缺失视为响应损坏（Parse），
 *   不生成残缺 URL；只有\"媒体源非空但全都不支持 Direct Stream\"才报\"需要转码\"；
 * - 播放 URL 永远不含 Token（Token 只走 headers，由 PlaybackEngine 注入 DataSource）；
 * - RequiredHttpHeaders 并入播放请求头，但鉴权头（X-Emby-Token / X-Emby-Authorization）
 *   不允许被覆盖（鉴权头后合并且获胜）。
 */
class EmbyPlaybackProvider(
    private val server: MediaServer,
    private val api: EmbyApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: EmbySessionStore,
    @Suppress("UNUSED_PARAMETER") private val logger: Logger,
) : MediaPlaybackProvider {
    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource {
        // Phase 1B-2.1：只支持视频型播放；音频走 Audio streaming 接口（未接入），
        // 禁止用 /Videos/{id}/stream 播放音频。
        if (item.type !in DIRECT_STREAM_TYPES) {
            throw ProviderException.NotYetImplemented(
                server.id,
                if (item.type == MediaType.AUDIO) "音频播放尚未接入" else "该媒体类型的播放尚未接入",
            )
        }
        // 无转码红线：任何显式要求转码/关闭直接流的选项组合直接拒绝（本阶段不实现 TRANSCODE）。
        if (options.forceTranscode || !options.enableDirectStream) {
            throw ProviderException.NotYetImplemented(server.id, "当前媒体需要转码")
        }
        val (token, userId) = requireSession()
        return try {
            val info = api.getPlaybackInfo(
                token = token,
                userId = userId,
                itemId = item.id,
                startTimeTicks = options.startPositionMs?.times(TICKS_PER_MILLIS),
                maxStreamingBitrate = options.maxBitrate,
            )
            val sources = info.mediaSources
            // 响应损坏（空 MediaSources）≠ 需要转码：明确报 Parse。
            if (sources.isEmpty()) throw ProviderException.Parse(server.id)
            val source = MediaSourceSelector.selectDirectStream(sources)
                ?: throw ProviderException.NotYetImplemented(server.id, "当前媒体需要转码")
            // Direct Stream 必备参数：缺了只能说明上游响应损坏，绝不生成残缺 URL。
            val mediaSourceId = source.id?.takeIf(String::isNotBlank)
                ?: throw ProviderException.Parse(server.id)
            val playSessionId = info.playSessionId?.takeIf(String::isNotBlank)
                ?: throw ProviderException.Parse(server.id)
            val container = source.container?.takeIf(String::isNotBlank)
                ?: item.container?.takeIf(String::isNotBlank)
                ?: throw ProviderException.Parse(server.id)
            val url = api.directStreamUrl(
                itemId = item.id,
                container = container,
                mediaSourceId = mediaSourceId,
                playSessionId = playSessionId,
            )
            val video = source.mediaStreams.firstOrNull { it.type?.lowercase() == "video" }
            val audio = source.mediaStreams.firstOrNull { it.type?.lowercase() == "audio" }
            val authenticatedHeaders = api.authenticatedHeaders(token, userId)
            val protectedSourceHeaders = source.requiredHttpHeaders.filterKeys { sourceHeader ->
                authenticatedHeaders.keys.none { authHeader ->
                    authHeader.equals(sourceHeader, ignoreCase = true)
                }
            }
            PlaybackSource(
                url = url,
                // Header 名大小写不敏感：先剔除与鉴权头同名（含不同大小写）的源级键，
                // 再写入权威鉴权值，避免 OkHttp/Media3 发送重复 Token/Authorization。
                headers = protectedSourceHeaders + authenticatedHeaders,
                container = container,
                videoCodec = video?.codec,
                audioCodec = audio?.codec,
                bitrate = source.bitrate,
                width = video?.width,
                height = video?.height,
                hdrType = EmbyDetailMapper.mapHdrType(
                    videoRange = video?.videoRange,
                    extendedVideoType = video?.extendedVideoType,
                    extendedVideoSubType = video?.extendedVideoSubType,
                ),
                durationMs = source.runTimeTicks?.div(TICKS_PER_MILLIS),
                mode = PlaybackMode.DIRECT_STREAM,
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

    private companion object {
        const val TICKS_PER_MILLIS = 10_000L
        /** Phase 1B-2.1：本阶段只支持视频型播放（无转码 Direct Stream）。 */
        val DIRECT_STREAM_TYPES = setOf(MediaType.MOVIE, MediaType.EPISODE, MediaType.VIDEO)
    }
}
