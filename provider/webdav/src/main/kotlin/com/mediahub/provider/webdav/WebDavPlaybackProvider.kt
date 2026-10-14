package com.mediahub.provider.webdav

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackSource
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.ProviderException

/**
 * WebDAV 直链播放（只读）。
 *
 * - 播放地址 = 条目自身的绝对 URL（`PROPFIND` 已解析同 origin）；**不构造任何 URL user-info**。
 * - Basic 凭据只通过 [PlaybackSource.headers] 传递（header-only 鉴权）。
 *   跨 origin 重定向的剥离由播放栈的 `OriginScopedCredentialInterceptor` 负责（ADR-030）；
 *   本类还会拒绝**起点就不在服务器 origin 内**的地址，避免凭据被带出的第一跳。
 * - 无服务端会话：`sessionId` 恒为 null，不做远端进度上报（本项目 WebDAV 为只读包）。
 * - Seek：依赖服务器 Range 支持。这里声明 `supportsSeeking=true`（多数 WebDAV 实现支持
 *   `Range`）；实际能力由播放器起播时的 `206` 探测决定，不在此处虚构。
 */
internal class WebDavPlaybackProvider(
    private val server: MediaServer,
    private val session: WebDavSession,
    private val logger: Logger,
) : MediaPlaybackProvider {

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource {
        if (item.type == MediaType.FOLDER) {
            throw ProviderException.NotYetImplemented(server.id, "WebDAV 目录播放")
        }
        val target = item.path ?: item.id

        // 拒绝 URL 内嵌凭据（user-info）——避免进入日志或被重定向带出。
        val userInfo = try {
            java.net.URI(target).userInfo
        } catch (e: Exception) {
            null
        }
        if (userInfo != null) {
            logger.w(LogTag.PROVIDER, "WebDAV 播放地址包含 user-info，已拒绝 serverId=${server.id}")
            throw ProviderException.Parse(server.id)
        }

        // 只允许服务器自身 origin：跨源地址意味着凭据会被带到第三方主机。
        val sameOrigin = WebDavUrls.resolveSameOrigin(session.baseUrl, target)
        if (sameOrigin == null) {
            logger.w(LogTag.PROVIDER, "WebDAV 播放地址不在服务器 origin 内，已拒绝 serverId=${server.id}")
            throw ProviderException.Parse(server.id)
        }

        return PlaybackSource(
            url = sameOrigin,
            headers = mapOf("Authorization" to session.authorization()),
            mimeType = contentTypeOrGuess(item),
            container = item.container,
            durationMs = item.runtimeMs,
            mode = PlaybackMode.DIRECT_PLAY,
            sessionId = null,
            supportsSeeking = true,
        )
    }

    private fun contentTypeOrGuess(item: MediaItem): String? = when (item.container?.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }
}
