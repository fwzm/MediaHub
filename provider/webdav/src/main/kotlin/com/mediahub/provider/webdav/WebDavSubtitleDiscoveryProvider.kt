package com.mediahub.provider.webdav

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.SubtitleFormats
import com.mediahub.provider.api.DiscoveredSubtitle
import com.mediahub.provider.api.MediaSubtitleDiscoveryProvider

/**
 * WebDAV 同目录字幕发现（P2 字幕中心切片一）。
 *
 * 实现：对视频的父目录做一次 `PROPFIND Depth:1`（与 [WebDavBrowseProvider] 同一协议
 * 通道与同 origin 约束），按字幕扩展名过滤：
 * - 只接受与服务器同 origin、不带 user-info 的 href（凭据不外流，红线与浏览一致）；
 * - 剔除视频自身与任何集合（目录）条目；
 * - 语言从文件名尾缀猜测（`.zh` / `.eng` 等），无尾缀为未知。
 *
 * 不做在线字幕站查询；不上传任何内容（用户约束红线）。
 */
internal class WebDavSubtitleDiscoveryProvider(
    private val server: MediaServer,
    private val api: WebDavApi,
    private val session: WebDavSession,
) : MediaSubtitleDiscoveryProvider {

    override suspend fun discoverSubtitles(video: MediaItem): List<DiscoveredSubtitle> {
        val videoUrl = video.path ?: video.id
        if (videoUrl.isBlank()) return emptyList()
        val folderUrl = WebDavUrls.normalizeBase(
            videoUrl.substringBeforeLast('/', missingDelimiterValue = videoUrl),
        )
        val resources = api.propfind(folderUrl, depth = 1, authorization = session.authorization())
        val videoKey = WebDavUrls.canonicalForCompare(videoUrl)

        return resources
            .mapNotNull { resource ->
                val resolved = WebDavUrls.resolveSameOrigin(folderUrl, resource.href) ?: return@mapNotNull null
                if (WebDavUrls.canonicalForCompare(resolved) == videoKey) return@mapNotNull null
                if (resource.isCollection ||
                    resource.href.substringBefore('?').substringBefore('#').endsWith("/")
                ) {
                    return@mapNotNull null
                }
                val name = resource.displayName?.takeIf { it.isNotBlank() }
                    ?: WebDavUrls.displayNameOf(resource.href).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in SubtitleFormats.EXTENSIONS) return@mapNotNull null
                DiscoveredSubtitle(
                    id = resolved,
                    name = name.substringBeforeLast('.'),
                    fileName = name,
                    extension = extension,
                    language = SubtitleFormats.languageFromFileName(name),
                    uri = resolved,
                )
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.extension }))
    }
}
