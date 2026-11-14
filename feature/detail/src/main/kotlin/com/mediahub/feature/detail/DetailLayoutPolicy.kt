package com.mediahub.feature.detail

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.model.HdrType
import com.mediahub.model.MediaVersion
import java.util.Locale

/** 详情页竖版海报宽度（条目 13/14/26）：按屏幕宽度分档，保持 2:3 高度自然增长。 */
internal fun detailPosterWidthDp(screenWidthDp: Int): Dp = when {
    screenWidthDp < 360 -> 92.dp
    screenWidthDp < 600 -> 104.dp
    else -> 132.dp
}

/**
 * 版本信息单行展示（条目 48）：只做展示，不参与播放源选择。
 * 缺失字段直接跳过，禁止用占位符污染可读性。
 */
internal fun formatVersionLine(version: MediaVersion): String = buildList {
    add(version.name)
    version.qualityLabel?.takeIf { it.isNotBlank() }?.let(::add)
    val resolution = when {
        version.width != null && version.height != null -> "${version.width}x${version.height}"
        version.height != null -> "${version.height}P"
        else -> null
    }
    resolution?.let(::add)
    if (version.hdrType != HdrType.NONE) add(version.hdrType.name)
    version.bitrate?.takeIf { it > 0 }?.let { add("${it / 1_000_000} Mbps") }
    version.sizeBytes?.takeIf { it > 0 }?.let { add(formatBytes(it)) }
}.joinToString(" · ")

/** 字节 → 人类可读（GB/MB，一位小数）。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
    val mb = bytes / 1_048_576.0
    return String.format(Locale.US, "%.1f MB", mb)
}

/**
 * 详情页底部路径展示（条目 24）：去掉 `file://` 前缀并做百分号解码。
 * 只做展示，不参与任何文件访问或播放寻址。
 */
internal fun decodedPathForDisplay(path: String?): String? {
    val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return percentDecode(raw.removePrefix("file://"))
}

private fun percentDecode(raw: String): String {
    val out = java.io.ByteArrayOutputStream(raw.length)
    var index = 0
    while (index < raw.length) {
        val ch = raw[index]
        if (ch == '%' && index + 2 < raw.length) {
            val decoded = raw.substring(index + 1, index + 3).toIntOrNull(16)
            if (decoded != null) {
                out.write(decoded)
                index += 3
                continue
            }
        }
        out.write(ch.toString().toByteArray(Charsets.UTF_8))
        index++
    }
    return out.toByteArray().toString(Charsets.UTF_8)
}
