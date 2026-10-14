package com.mediahub.feature.player

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mediahub.core.ui.effects.PlayerVisualPalette
import com.mediahub.core.ui.effects.PlayerVisualSettingsPanel
import com.mediahub.core.ui.effects.SpectrumProvider
import com.mediahub.core.ui.effects.VisualPalette
import com.mediahub.model.PlayerVisualEffectsPreferences

/** Production sheet shared by the in-player entry; content is the same editor used by Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerVisualEffectsSheet(
    preferences: PlayerVisualEffectsPreferences,
    previewPalette: VisualPalette,
    spectrum: SpectrumProvider,
    audioReactiveAvailable: Boolean?,
    showAudioAccessAction: Boolean,
    audioAccessActionIsRetry: Boolean,
    lifecycleStarted: Boolean,
    onPreferencesChanged: (PlayerVisualEffectsPreferences) -> Unit,
    onRequestAudioAccess: () -> Unit,
    onRestoreDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chrome = remember(previewPalette) { PlayerVisualPalette.from(previewPalette) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = chrome.surface,
        contentColor = chrome.onSurface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = chrome.onSurfaceVariant)
        },
    ) {
        PlayerVisualSettingsPanel(
            preferences = preferences,
            onPreferencesChanged = onPreferencesChanged,
            onRestoreDefaults = onRestoreDefaults,
            previewPalette = previewPalette,
            spectrum = spectrum,
            audioReactiveAvailable = audioReactiveAvailable,
            onRequestAudioAccess = onRequestAudioAccess,
            showAudioAccessAction = showAudioAccessAction,
            audioAccessActionIsRetry = audioAccessActionIsRetry,
            previewRunning = lifecycleStarted,
        )
    }
}
