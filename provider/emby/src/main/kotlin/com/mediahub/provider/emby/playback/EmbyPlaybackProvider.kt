package com.mediahub.provider.emby.playback

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiException
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
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
 * Emby 播放（Phase 1B-2，无转码 Direct Stream）。
 *
 * 红线（任务书 + ADR-026）：
 * - 只接受 SupportsDirectStream==true 的 MediaSource，否则 NotYetImplemented("需要转码")；
 * - 播放 URL 永远不含 Token（Token 只走 headers，由 PlaybackEngine 注入 DataSource）；
 * - 不做转码、不做 provider 特判。
 */
class EmbyPlaybackProvider(
    private val server: MediaServer,
    private val api: EmbyApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: EmbySessionStore,
    @Suppress("UNUSED_PARAMETER") private val logger: Logger,
) : MediaPlaybackProvider {

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource {
        val (token, userId) = requireSession()
        // 无转码红线：任何显式要求转码/关闭直接流的选项组合直接拒绝（本阶段不实现 TRANSCODE）。
        if (options.forceTranscode || !options.enableDirectStream) {
            throw ProviderException.NotYetImplemented(server.id, "需要转码")
        }
        return try {
            val info = api.getPlaybackInfo(
                token = token,
                userId = userId,
                itemId = item.id,
                startTimeTicks = options.startPositionMs?.times(TICKS_PER_MILLIS),
                maxStreamingBitrate = options.maxBitrate,
            )
            val source = MediaSourceSelector.selectDirectStream(info.mediaSources)
                ?: throw ProviderException.NotYetImplemented(server.id, "需要转码")
            val container = source.container?.takeIf(String::isNotBlank)
                ?: item.container?.takeIf(String::isNotBlank)
                ?: throw ProviderException.Parse(server.id)
            val url = api.directStreamUrl(
                itemId = item.id,
                container = container,
                mediaSourceId = source.id,
                playSessionId = info.playSessionId,
            )
            val video = source.mediaStreams.firstOrNull { it.type?.lowercase() == "video" }
            val audio = source.mediaStreams.firstOrNull { it.type?.lowercase() == "audio" }
            PlaybackSource(
                url = url,
                headers = api.authenticatedHeaders(token, userId),
                container = container,
                videoCodec = video?.codec,
                audioCodec = audio?.codec,
                bitrate = source.bitrate,
                width = video?.width,
                height = video?.height,
                hdrType = EmbyDetailMapper.mapHdrType(video?.videoRange),
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
    }
}
