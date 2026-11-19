package com.mediahub.feature.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediahub.core.ui.ServerIcon

internal const val PLAYER_SERVER_IDENTITY_ICON_TAG = "player_server_identity_icon"

/** 播放器信息层的媒体源标识（统一 ServerIcon 图标 + 名称）。 */
@Composable
internal fun PlayerServerIdentity(
    displayName: String,
    icon: String?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ServerIcon(
            icon = icon,
            label = displayName,
            modifier = Modifier
                .size(20.dp)
                .testTag(PLAYER_SERVER_IDENTITY_ICON_TAG),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = displayName,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}
