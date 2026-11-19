package com.mediahub.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 次级信息簇的测试标签：审计 P10 要求速度/电量只占右下角一小簇。 */
internal const val PLAYER_SECONDARY_INFO_TAG = "player_secondary_info"

/** 两个字段之间的分隔符；测试固定它为「 · 」而不是整行两端对齐。 */
private const val PLAYER_SECONDARY_INFO_SEPARATOR = " · "

/**
 * 把「下载速度」与「电量」合并成一行次级信息；任一字段为空/空白时不占位，
 * 两者都没有时返回 null，调用方据此完全不渲染这一簇。
 */
internal fun playerSecondaryInfoLabel(speedText: String, batteryText: String?): String? {
    val parts = listOfNotNull(
        speedText.trim().takeIf { it.isNotEmpty() },
        batteryText?.trim()?.takeIf { it.isNotEmpty() },
    )
    return if (parts.isEmpty()) null else parts.joinToString(separator = PLAYER_SECONDARY_INFO_SEPARATOR)
}

/**
 * 播放控制层右下角的次级信息簇（审计 P10）。
 *
 * 取代原先「左速度 / 右电量」两端对齐的整行：那一行把进度条上下撑松，
 * 且两个低频信息占满了整条宽度。现在它们合并成一行小字，由调用方贴右下角。
 */
@Composable
internal fun PlayerSecondaryInfo(speedText: String, batteryText: String?, modifier: Modifier = Modifier) {
    val label = playerSecondaryInfoLabel(speedText, batteryText) ?: return
    Row(
        modifier = modifier.testTag(PLAYER_SECONDARY_INFO_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
