package com.mediahub.provider.webdav

import com.mediahub.model.MediaItem
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.provider.api.MediaBrowseProvider
import com.mediahub.model.MediaServer

/**
 * WebDAV 只读目录浏览（`PROPFIND Depth: 1`）。
 *
 * 语义边界（不得夸大）：
 * - **无服务端分页**。PROPFIND 一次返回整个目录，本类对结果做**本地切片**
 *   （`offset` / `limit` / `nextOffset`），不声明任何服务端分页或 `SEARCH` 能力。
 * - **目录自身条目必须剔除**：RFC 4918 要求 `Depth: 1` 的响应包含被请求集合本身。
 * - `href` 只在**同一 origin** 内解析；跨 origin 条目直接丢弃（凭据不外流）。
 * - 服务器未给 `displayname` 时回落到 href 末段，并做 percent-decoding
 *   （中文 / 空格 / `+` 不得被破坏）。
 */
internal class WebDavBrowseProvider(
    private val server: MediaServer,
    private val api: WebDavApi,
    private val session: WebDavSession,
) : MediaBrowseProvider {

    override suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> {
        val requested = folder?.path ?: folder?.id
        val folderUrl = WebDavUrls.normalizeBase(requested ?: session.baseUrl)
        val authorization = session.authorization()
        val resources = api.propfind(folderUrl, depth = 1, authorization = authorization)
        val selfKey = WebDavUrls.canonicalForCompare(folderUrl)

        val children = resources
            .mapNotNull { it.toMediaItem(folderUrl, selfKey) }
            .sortedWith(compareBy({ !it.isContainer }, { it.sortName.lowercase() }))

        val limit = page.limit.coerceAtLeast(1)
        val offset = page.offset.coerceAtLeast(0)
        val sliced = children.drop(offset).take(limit)
        val nextOffset = offset + sliced.size
        val hasMore = nextOffset < children.size
        return PagedResult(
            items = sliced,
            totalCount = children.size,
            hasMore = hasMore,
            nextOffset = if (hasMore) nextOffset else null,
        )
    }

    private fun WebDavResource.toMediaItem(folderUrl: String, selfKey: String): MediaItem? {
        val resolved = WebDavUrls.resolveSameOrigin(folderUrl, href) ?: return null
        // 目录自身条目：href 与被请求集合指向同一资源时剔除。
        if (WebDavUrls.canonicalForCompare(resolved) == selfKey) return null

        val name = displayName?.takeIf { it.isNotBlank() }
            ?: WebDavUrls.displayNameOf(href).takeIf { it.isNotBlank() }
            ?: return null

        // 集合判定：resourcetype 含 DAV:collection，或 href 以 `/` 结尾（部分服务器只给后者）。
        val collection = isCollection || href.substringBefore('?').substringBefore('#').endsWith("/")

        return MediaItem(
            serverId = server.id,
            id = resolved,
            type = WebDavUrls.mediaTypeOf(name, collection),
            title = name,
            libraryId = WEBDAV_LIBRARY_ID,
            parentId = folderUrl,
            path = resolved,
            sizeBytes = if (collection) null else contentLength,
            container = if (collection) null else WebDavUrls.containerOf(name),
            sortName = name,
        )
    }

    private val MediaItem.isContainer: Boolean
        get() = type.isContainer

    private companion object {
        const val WEBDAV_LIBRARY_ID = "webdav"
    }
}
