package com.mediahub.feature.detail.version

import com.mediahub.model.HdrType
import com.mediahub.model.MediaVersion
import java.util.Locale

/** 详情页版本行（纯展示模型）。 */
data class VersionRow(
    val id: String,
    val label: String,
    val detail: String?,
    val isSelected: Boolean,
)

/**
 * 多版本面板显隐：只有 >=2 个版本才值得让用户选；恰好一个版本时直接播放默认源。
 */
fun shouldShowVersionPanel(versions: List<MediaVersion>): Boolean = versions.size >= 2

/**
 * 版本行投影。服务端返回顺序即优先级（首项 = 默认版本），**不重排**。
 * [selectedId] 为 null 时首项视为选中；指定的 id 不存在时任何行都不标记选中，
 * 避免用户以为选中了一个实际不会被播放的版本。
 */
fun versionRows(versions: List<MediaVersion>, selectedId: String?): List<VersionRow> =
    versions.mapIndexed { index, version ->
        val selected = if (selectedId == null) {
            index == 0
        } else {
            version.id == selectedId
        }
        VersionRow(
            id = version.id,
            label = versionLabel(version, index),
            detail = versionDetail(version),
            isSelected = selected,
        )
    }

private fun versionLabel(version: MediaVersion, index: Int): String =
    version.qualityLabel?.takeIf { it.isNotBlank() }
        ?: version.name.takeIf { it.isNotBlank() }
        ?: "版本 ${index + 1}"

private fun versionDetail(version: MediaVersion): String? {
    val parts = buildList {
        when {
            version.width != null && version.height != null -> add("${version.width}×${version.height}")
            version.height != null -> add(version.height.toString())
            version.width != null -> add(version.width.toString())
        }
        hdrLabel(version.hdrType)?.let(::add)
        version.bitrate?.takeIf { it > 0 }?.let { add(String.format(Locale.ROOT, "%.1f Mbps", it / 1_000_000.0)) }
        version.sizeBytes?.takeIf { it > 0 }?.let { add(String.format(Locale.ROOT, "%.1f GB", it / 1_000_000_000.0)) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun hdrLabel(hdrType: HdrType): String? = when (hdrType) {
    HdrType.NONE -> null
    HdrType.HDR10 -> "HDR10"
    HdrType.HDR10_PLUS -> "HDR10+"
    HdrType.HLG -> "HLG"
    HdrType.DOLBY_VISION -> "杜比视界"
}
