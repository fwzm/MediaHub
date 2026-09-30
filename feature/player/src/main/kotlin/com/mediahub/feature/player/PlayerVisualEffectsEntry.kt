package com.mediahub.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mediahub.core.ui.effects.PlayerVisualTestTags

/** Visible, localized PlayerControls entry (kept standalone for Compose path tests). */
@Composable
internal fun PlayerVisualEffectsEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.testTag(PlayerVisualTestTags.PLAYER_ENTRY),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = stringResource(R.string.player_visual_effects_entry_description),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.player_visual_effects_entry),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
