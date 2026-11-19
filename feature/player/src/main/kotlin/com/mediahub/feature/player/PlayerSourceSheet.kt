package com.mediahub.feature.player

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
import com.mediahub.model.PlaybackSourceOption

/** Bottom sheet listing the aggregated playable lines of the current item. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSourceSheet(
    choices: List<PlayerSourceChoice>,
    onDismiss: () -> Unit,
    onSelect: (PlaybackSourceOption) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        PlayerSourceSheetContent(choices = choices, onSelect = onSelect)
    }
}

/** Sheet body kept separate from the modal window so layout and clicks stay testable. */
@Composable
internal fun PlayerSourceSheetContent(
    choices: List<PlayerSourceChoice>,
    onSelect: (PlaybackSourceOption) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.player_sources),
            style = MaterialTheme.typography.titleMedium,
        )
        choices.forEach { choice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(choice.option) }
                    .padding(vertical = 8.dp),
            ) {
                RadioButton(
                    selected = choice.isCurrent,
                    onClick = { onSelect(choice.option) },
                )
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(text = choice.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = choice.option.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
