package com.mediahub.model

/**
 * HLS（m3u8）内容识别。
 *
 * 背景（条目 136）：部分流地址没有扩展名（如 `/live/channel`、`/play?id=1`），
 * 既推断不出容器，服务端也可能返回 `application/octet-stream`。
 * 此时只能按**响应内容**判断：`Content-Type` 是 HLS MIME，或响应体以
 * `#EXTM3U` 播放列表标记开头。
 *
 * 本类只做纯判定，不做网络 IO；探测调用方负责取有限前缀（见 WebDAV Provider）。
 */
object HlsContentDetector {

    /** Media3 识别的 HLS MIME（`MimeTypes.APPLICATION_M3U8`）。 */
    const val MIME_TYPE: String = "application/x-mpegurl"

    private val HLS_CONTENT_TYPES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        "application/mpegurl",
    )

    private const val PLAYLIST_MARKER = "#EXTM3U"

    /**
     * @param contentType 响应 `Content-Type`（可含 charset 等参数；null = 未提供）
     * @param bodyPrefix 响应体前若干字节（null = 未读取）
     * @return 识别为 HLS 时返回 [MIME_TYPE]，否则 null
     */
    fun detect(contentType: String?, bodyPrefix: String?): String? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type in HLS_CONTENT_TYPES) return MIME_TYPE

        // BOM / 前导空白不改变播放列表标记，避免把合法 m3u8 当成未知内容。
        val body = bodyPrefix?.trimStart('\uFEFF', ' ', '\t', '\r', '\n').orEmpty()
        if (body.startsWith(PLAYLIST_MARKER)) return MIME_TYPE
        return null
    }
}
