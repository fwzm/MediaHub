package com.mediahub.feature.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mediahub.core.ui.effects.PlayerVisualTestTags

/** Visible, localized PlayerControls entry (kept standalone for Compose path tests). */
@Composable
internal fun PlayerVisualEffectsEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.testTag(PlayerVisualTestTags.PLAYER_ENTRY),
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = stringResource(R.string.player_visual_effects_entry_description),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
