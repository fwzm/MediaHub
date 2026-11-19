package com.mediahub.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** 主控按钮组的 ±10 秒步进（UI 视觉审计 P9）：播放键左右各一个。 */
internal const val PLAYER_SEEK_STEP_MS = 10_000L

/**
 * 把一次 ±10s 步进夹到媒体区间内：
 * - 小于 0 一律回到 0（不能退到片头之前）；
 * - 时长已知时不超过 durationMs（不能用步进越过片尾）；
 * - 时长未知（<=0，直播/元数据缺失）时只做下界保护。
 */
internal fun playerSeekTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    val requested = positionMs + deltaMs
    if (durationMs <= 0L) return requested.coerceAtLeast(0L)
    return requested.coerceIn(0L, durationMs)
}

/**
 * 播放页主控：后退 10s / 播放暂停 / 前进 10s。
 *
 * 图标同时携带本地化 contentDescription，触摸与 TalkBack 都能定位；
 * 播放键沿用 48dp 圆形主色容器（G3 结论：图标跟随 playWhenReady，不跟随 isPlaying）。
 */
@Composable
internal fun PlayerTransportControls(
    showPauseIcon: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onSeekBy(-PLAYER_SEEK_STEP_MS) }) {
            Icon(
                Icons.Default.Replay10,
                contentDescription = stringResource(R.string.player_seek_back_10),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
        }
        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                if (showPauseIcon) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(
                    if (showPauseIcon) R.string.player_pause else R.string.player_play,
                ),
                modifier = Modifier.size(26.dp),
            )
        }
        IconButton(onClick = { onSeekBy(PLAYER_SEEK_STEP_MS) }) {
            Icon(
                Icons.Default.Forward10,
                contentDescription = stringResource(R.string.player_seek_forward_10),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
