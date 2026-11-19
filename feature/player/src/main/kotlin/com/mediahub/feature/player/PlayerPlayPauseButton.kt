package com.mediahub.feature.player

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 播放/暂停按钮：可见性由用户偏好控制。
 *
 * 隐藏只影响按钮本身；手势、媒体键与播放状态都不受影响。
 */
@Composable
internal fun PlayerPlayPauseButton(
    visible: Boolean,
    showPauseIcon: Boolean,
    onTogglePlayPause: () -> Unit,
) {
    if (!visible) return
    FilledIconButton(
        onClick = onTogglePlayPause,
        modifier = Modifier
            .size(48.dp)
            .testTag("player.play_pause"),
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
}
