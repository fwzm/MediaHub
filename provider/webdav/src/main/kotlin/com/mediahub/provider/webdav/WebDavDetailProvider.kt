package com.mediahub.provider.webdav

import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.api.ProviderException

/**
 * WebDAV 文件详情（`PROPFIND Depth: 0`）。
 *
 * 语义边界（与 Browse/Playback 同一契约，不得夸大）：
 * - **无海报/评分/简介/多版本**：WebDAV 是裸文件系统，[MediaDetail] 除 `item` 外
 *   全部为空。详情页按既有降级路径展示文件名/大小/类型，不伪造元数据。
 * - **目录不产生详情**：`itemId` 指向集合时抛 [ProviderException.NotFound]——
 *   目录的进入方式是 Browse（listFolder），不能当影片详情/播放。
 * - **同 origin 边界**：`itemId` 必须能解析回与服务器相同的 origin 且不带 user-info，
 *   否则视为非法引用直接拒绝（Basic 凭据只发给本服务器）。
 * - `itemId` 即 Browse 产出的**同 origin 绝对 URL**（保持 RFC 3986 编码形态），
 *   本类不对它做二次解码——PROPFIND 目标就是该 URL 本身。
 */
internal class WebDavDetailProvider(
    private val server: MediaServer,
    private val api: WebDavApi,
    private val session: WebDavSession,
) : MediaDetailProvider {

    override suspend fun getItemDetail(itemId: String): MediaDetail {
        // 非法引用（跨 origin / user-info / 无法解析）：在发起任何网络请求前拒绝。
        val target = WebDavUrls.resolveSameOrigin(session.baseUrl, itemId)
            ?: throw ProviderException.NotFound(server.id, "文件")

        val authorization = session.authorization()
        // Depth:0 打在文件本身，不加尾部 `/`（normalizeBase 是目录语义，会把文件变集合引用）
        val resources = api.propfind(target, depth = 0, authorization = authorization)
        val self = resources.firstOrNull()
            ?: throw ProviderException.Parse(server.id, IllegalStateException("Depth:0 响应为空"))

        val name = self.displayName?.takeIf { it.isNotBlank() }
            ?: WebDavUrls.displayNameOf(self.href).takeIf { it.isNotBlank() }
            ?: throw ProviderException.Parse(server.id)

        // 集合判定与 Browse 完全一致：resourcetype 或 href 尾 `/`。
        val collection = self.isCollection ||
            self.href.substringBefore('?').substringBefore('#').endsWith("/")
        if (collection) throw ProviderException.NotFound(server.id, "目录没有影片详情")

        return MediaDetail(
            item = MediaItem(
                serverId = server.id,
                id = target,
                type = WebDavUrls.mediaTypeOf(name, isCollection = false),
                title = name,
                libraryId = WEBDAV_LIBRARY_ID,
                parentId = session.baseUrl,
                path = target,
                sizeBytes = self.contentLength,
                container = WebDavUrls.containerOf(name),
                sortName = name,
            ),
        )
    }

    private companion object {
        const val WEBDAV_LIBRARY_ID = "webdav"
    }
}
