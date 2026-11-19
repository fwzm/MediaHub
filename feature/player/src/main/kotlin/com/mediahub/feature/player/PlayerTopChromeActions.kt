package com.mediahub.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource

/**
 * 审计 P4：播放页顶部 chrome 的四个入口统一为图标按钮。
 *
 * 背景：旧实现把「特效/信息/音轨/字幕」排成一行文字按钮，窄屏/竖屏时
 * 会挤压标题并占据过多画面。图标按钮保留同样的四个动作与本地化
 * contentDescription（无障碍/自动化可发现），但不占用文字宽度。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerTopChromeActions(
    onShowVisualEffects: () -> Unit,
    onShowInfo: () -> Unit,
    onShowAudio: () -> Unit,
    onShowSubtitle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        PlayerVisualEffectsEntry(onClick = onShowVisualEffects)
        PlayerTopChromeIconButton(
            icon = Icons.Default.Info,
            contentDescriptionRes = R.string.player_info_entry_description,
            onClick = onShowInfo,
        )
        PlayerTopChromeIconButton(
            icon = Icons.Default.Audiotrack,
            contentDescriptionRes = R.string.player_audio_tracks_description,
            onClick = onShowAudio,
        )
        PlayerTopChromeIconButton(
            icon = Icons.Default.Subtitles,
            contentDescriptionRes = R.string.player_subtitles_description,
            onClick = onShowSubtitle,
        )
    }
}

@Composable
private fun PlayerTopChromeIconButton(
    icon: ImageVector,
    contentDescriptionRes: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescriptionRes),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
