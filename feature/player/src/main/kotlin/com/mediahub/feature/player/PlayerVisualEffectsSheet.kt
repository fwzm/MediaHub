package com.mediahub.feature.player

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.mediahub.core.ui.effects.PlayerVisualPalette
import com.mediahub.core.ui.effects.PlayerVisualSettingsPanel
import com.mediahub.core.ui.effects.SpectrumProvider
import com.mediahub.core.ui.effects.VisualPalette
import com.mediahub.model.PlayerVisualEffectsPreferences

/** Production sheet shared by the in-player entry; content is the same editor used by Settings. */
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

    PlayerSheetScaffold(
        onDismiss = onDismiss,
        containerColor = chrome.surface,
        contentColor = chrome.onSurface,
        dragHandleColor = chrome.onSurfaceVariant,
        // 编辑器自带 verticalScroll 与 20dp/12dp 内边距：这里不再叠加滚动和留白。
        scrollable = false,
        contentPadding = PaddingValues(0.dp),
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
