package com.mediahub.feature.player

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mediahub.model.PlayerAspectRatio

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerAspectRatioSheet(
    selected: PlayerAspectRatio,
    onDismiss: () -> Unit,
    onSelect: (PlayerAspectRatio) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        PlayerAspectRatioSheetContent(selected = selected, onSelect = onSelect)
    }
}

@Composable
internal fun PlayerAspectRatioSheetContent(
    selected: PlayerAspectRatio,
    onSelect: (PlayerAspectRatio) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.player_aspect_ratio),
            style = MaterialTheme.typography.titleMedium,
        )
        PlayerAspectRatio.entries.forEach { ratio ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(ratio) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = ratio == selected,
                    onClick = { onSelect(ratio) },
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = stringResource(ratio.labelRes()),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@StringRes
private fun PlayerAspectRatio.labelRes(): Int = when (this) {
    PlayerAspectRatio.FIT -> R.string.player_aspect_fit
    PlayerAspectRatio.FILL -> R.string.player_aspect_fill
    PlayerAspectRatio.RATIO_16_9 -> R.string.player_aspect_16_9
    PlayerAspectRatio.RATIO_4_3 -> R.string.player_aspect_4_3
}
