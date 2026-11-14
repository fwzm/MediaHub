package com.mediahub.provider.iptv

import java.security.MessageDigest

/** 解析后的 IPTV 频道（Provider 内部模型，不暴露给 UI 层）。 */
internal data class IptvChannel(
    /** Provider 自己的稳定频道 id（重复 tvg-id 会追加序号），即 `MediaItem.id`。 */
    val id: String,
    val name: String,
    /** 真实直播流地址；只驻留内存，绝不写入 MediaItem.path / 数据库。 */
    val streamUrl: String,
    val group: String,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
) {
    /** EPG 对齐用的频道 id：M3U 的 tvg-id 优先，缺省回落到 Provider id。 */
    val epgChannelId: String get() = tvgId?.takeIf { it.isNotBlank() } ?: id
}

/** 一次 M3U 解析的结果。 */
internal data class M3uPlaylist(
    val channels: List<IptvChannel>,
    /** `#EXTM3U url-tvg="..."` / `x-tvg-url` 声明的 EPG 地址（可能为空）。 */
    val epgUrl: String? = null,
)

/**
 * M3U / M3U8 直播播放列表解析器。
 *
 * 只做纯文本解析：不发请求、不落库、不依赖 Android API，便于 JVM 单测。
 * 支持的常见扩展：
 * - `#EXTINF:-1 tvg-id tvg-name tvg-logo group-title,频道名`
 * - `#EXTGRP:分组`（优先级高于 `group-title`，只作用于紧随的一个频道）
 * - `#EXTVLCOPT` / `#KODIPROP` 等指令忽略
 * - `#EXTM3U url-tvg="..."` 头部 EPG 声明
 *
 * 无法识别的条目（缺 URL、非绝对 URL）直接跳过，不中断整张列表。
 */
internal object M3uParser {

    private const val UNGROUPED = "未分组"
    private const val UNNAMED = "未命名频道"

    private val ATTRIBUTE = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val ABSOLUTE_URL = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun parse(raw: String): M3uPlaylist {
        val text = raw
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        var sawHeader = false
        var epgUrl: String? = null
        val channels = ArrayList<IptvChannel>()
        val idUsage = HashMap<String, Int>()

        var pending: PendingEntry? = null
        var pendingGroup: String? = null

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                    sawHeader = true
                    val attrs = attributes(trimmed)
                    epgUrl = attrs["url-tvg"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: attrs["x-tvg-url"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: epgUrl
                }

                trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseExtInf(trimmed)
                    // 不清空 pendingGroup：EXTGRP 允许出现在 EXTINF 之前或之后，
                    // 都在下一个 URL 行生效；URL 行消费后清空。
                }

                trimmed.startsWith("#EXTGRP", ignoreCase = true) -> {
                    pendingGroup = trimmed.substringAfter(':', "")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                }

                trimmed.startsWith("#") -> {
                    // #EXTVLCOPT / #KODIPROP / #EXTIMG 等：不影响频道模型，忽略。
                }

                else -> {
                    val entry = pending ?: continue
                    if (!ABSOLUTE_URL.containsMatchIn(trimmed)) {
                        pending = null
                        pendingGroup = null
                        continue
                    }
                    val group = pendingGroup
                        ?: entry.group
                        ?: UNGROUPED
                    val baseId = entry.tvgId?.takeIf { it.isNotBlank() }
                        ?: "iptv-${stableToken(trimmed)}"
                    val seen = idUsage[baseId] ?: 0
                    idUsage[baseId] = seen + 1
                    val id = if (seen == 0) baseId else "$baseId#${seen + 1}"

                    val name = entry.name
                        ?.takeIf { it.isNotBlank() }
                        ?: entry.tvgName?.takeIf { it.isNotBlank() }
                        ?: fallbackName(trimmed)

                    channels += IptvChannel(
                        id = id,
                        name = name,
                        streamUrl = trimmed,
                        group = group,
                        logoUrl = entry.logoUrl,
                        tvgId = entry.tvgId,
                        tvgName = entry.tvgName,
                    )
                    pending = null
                    pendingGroup = null
                }
            }
        }

        require(sawHeader) { "M3U 播放列表缺少 #EXTM3U 头" }
        return M3uPlaylist(channels = channels, epgUrl = epgUrl)
    }

    private data class PendingEntry(
        val name: String?,
        val tvgId: String?,
        val tvgName: String?,
        val logoUrl: String?,
        val group: String?,
    )

    private fun parseExtInf(line: String): PendingEntry {
        val payload = line.substringAfter(':', "")
        val comma = firstUnquotedComma(payload)
        val head = if (comma >= 0) payload.substring(0, comma) else payload
        val name = if (comma >= 0) payload.substring(comma + 1).trim() else null
        val attrs = attributes(head)
        return PendingEntry(
            name = name,
            tvgId = attrs["tvg-id"]?.trim(),
            tvgName = attrs["tvg-name"]?.trim(),
            logoUrl = attrs["tvg-logo"]?.trim()?.takeIf { it.isNotEmpty() },
            group = attrs["group-title"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun attributes(text: String): Map<String, String> =
        ATTRIBUTE.findAll(text).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun firstUnquotedComma(text: String): Int {
        var inQuotes = false
        for (index in text.indices) {
            when (text[index]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return index
            }
        }
        return -1
    }

    private fun fallbackName(streamUrl: String): String {
        val path = streamUrl.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: UNNAMED
    }

    /** URL 的短稳定摘要；用于没有 tvg-id 的频道生成可复现 id。 */
    private fun stableToken(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
