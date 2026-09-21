package com.mediahub.core.ui.effects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.unit.dp
import com.mediahub.core.ui.R
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.PlayerVisualPreset
import com.mediahub.model.VisualPerformanceMode
import kotlin.math.roundToInt

/** Stable semantics identifiers shared by production UI and Compose acceptance tests. */
object PlayerVisualTestTags {
    const val OVERLAY = "player_visual_effects_overlay"
    const val PLAYER_CONTROLS = "player_visual_controls"
    const val CHROME_AMBIENT = "player_visual_chrome_ambient"
    const val PLAYER_ENTRY = "player_visual_effects_entry"
    const val SETTINGS_ENTRY = "settings_visual_effects_entry"
    const val SETTINGS = "player_visual_effects_settings"
    const val PREVIEW = "player_visual_effects_preview"
    const val ENABLED = "player_visual_effects_enabled"
    const val PRESET_OFF = "player_visual_preset_off"
    const val PRESET_AURORA = "player_visual_preset_aurora"
    const val PRESET_LIQUID = "player_visual_preset_liquid"
    const val PRESET_SPECTRUM = "player_visual_preset_spectrum"
    const val INTENSITY = "player_visual_intensity"
    const val FOLLOW_ARTWORK = "player_visual_follow_artwork"
    const val AUDIO_REACTIVE = "player_visual_audio_reactive"
    const val AUDIO_UNAVAILABLE = "player_visual_audio_unavailable"
    const val AUDIO_PERMISSION_ACTION = "player_visual_audio_permission_action"
    const val RESTORE_DEFAULTS = "player_visual_restore_defaults"

    fun performance(mode: VisualPerformanceMode): String =
        "player_visual_performance_${mode.name.lowercase()}"
}

/** Localized product name for a persisted renderer preset. */
@Composable
fun playerVisualPresetName(preset: PlayerVisualPreset): String = when (preset) {
    PlayerVisualPreset.AURORA -> stringResource(R.string.player_visual_preset_aurora)
    PlayerVisualPreset.LIQUID -> stringResource(R.string.player_visual_preset_liquid)
    PlayerVisualPreset.SPECTRUM -> stringResource(R.string.player_visual_preset_spectrum)
}

/** Localized product name for a performance mode. */
@Composable
fun visualPerformanceModeName(mode: VisualPerformanceMode): String = when (mode) {
    VisualPerformanceMode.AUTO -> stringResource(R.string.player_visual_performance_auto)
    VisualPerformanceMode.BATTERY -> stringResource(R.string.player_visual_performance_battery)
    VisualPerformanceMode.BALANCED -> stringResource(R.string.player_visual_performance_balanced)
    VisualPerformanceMode.HIGH -> stringResource(R.string.player_visual_performance_high)
}

/**
 * Shared production editor used by both PlayerScreen and Settings. The preview delegates to
 * [PlayerVisualEffectPreview], which itself delegates to the same [PlayerVisualRenderer] used by
 * playback; there is no parallel demo renderer.
 */
@Composable
fun PlayerVisualSettingsPanel(
    preferences: PlayerVisualEffectsPreferences,
    onPreferencesChanged: (PlayerVisualEffectsPreferences) -> Unit,
    onRestoreDefaults: () -> Unit,
    modifier: Modifier = Modifier,
    previewPalette: VisualPalette? = null,
    spectrum: SpectrumProvider = SpectrumProvider.Noop,
    audioReactiveAvailable: Boolean? = null,
    onRequestAudioAccess: (() -> Unit)? = null,
    showAudioAccessAction: Boolean = false,
    audioAccessActionIsRetry: Boolean = false,
    previewRunning: Boolean = true,
) {
    val normalized = preferences.normalized()
    val context = LocalContext.current
    val powerSave = rememberPowerSaveMode(context)
    val reduceMotion = rememberReduceMotion(context)
    val previewDecision = VisualFramePolicy.resolvePreview(
        enabled = normalized.enabled && previewRunning,
        lifecycleStarted = previewRunning,
        performanceMode = normalized.performanceMode,
        intensity = normalized.intensity,
        powerSave = powerSave,
        reduceMotion = reduceMotion,
    )
    val sourcePalette = previewPalette ?: PlayerVisualPresetMapper.resolve(
        preset = normalized.preset,
        intensity = normalized.intensity,
        targetFps = previewDecision.targetFps.coerceAtLeast(1),
        motionScale = previewDecision.motionScale,
    ).palette
    val chrome = remember(sourcePalette) { PlayerVisualPalette.from(sourcePalette) }
    var backend by remember { mutableStateOf(RendererBackend.NONE) }

    PlayerVisualTheme(palette = chrome) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag(PlayerVisualTestTags.SETTINGS),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.player_visual_effects_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.player_visual_preview),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlayerVisualEffectPreview(
                request = PlayerVisualRenderRequest(
                    preset = normalized.preset,
                    palette = sourcePalette,
                    intensity = normalized.intensity,
                    audioReactive = normalized.audioReactive && audioReactiveAvailable != false,
                    frameDecision = previewDecision,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .testTag(PlayerVisualTestTags.PREVIEW),
                spectrum = spectrum,
                progressProvider = { 0.42f },
                onBackendChanged = { backend = it },
            )
            Text(
                text = stringResource(
                    R.string.player_visual_backend_format,
                    when (backend) {
                        RendererBackend.RUNTIME_SHADER -> stringResource(R.string.player_visual_backend_shader)
                        RendererBackend.FALLBACK_GRADIENT -> stringResource(R.string.player_visual_backend_fallback)
                        RendererBackend.NONE -> stringResource(R.string.player_visual_backend_stopped)
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            VisualSettingSwitch(
                label = stringResource(R.string.player_visual_enabled),
                checked = normalized.enabled,
                tag = PlayerVisualTestTags.ENABLED,
                onCheckedChange = { enabled ->
                    onPreferencesChanged(normalized.copy(enabled = enabled))
                },
            )

            HorizontalDivider()
            Text(
                text = stringResource(R.string.player_visual_preset),
                style = MaterialTheme.typography.titleMedium,
            )
            PresetOption(
                title = stringResource(R.string.player_visual_preset_off),
                description = stringResource(R.string.player_visual_preset_off_description),
                selected = !normalized.enabled,
                tag = PlayerVisualTestTags.PRESET_OFF,
                chrome = chrome,
                onClick = { onPreferencesChanged(normalized.copy(enabled = false)) },
            )
            PlayerVisualPreset.entries.forEach { preset ->
                PresetOption(
                    title = playerVisualPresetName(preset),
                    description = presetDescription(preset),
                    selected = normalized.enabled && normalized.preset == preset,
                    tag = when (preset) {
                        PlayerVisualPreset.AURORA -> PlayerVisualTestTags.PRESET_AURORA
                        PlayerVisualPreset.LIQUID -> PlayerVisualTestTags.PRESET_LIQUID
                        PlayerVisualPreset.SPECTRUM -> PlayerVisualTestTags.PRESET_SPECTRUM
                    },
                    chrome = chrome,
                    onClick = {
                        onPreferencesChanged(normalized.copy(enabled = true, preset = preset))
                    },
                )
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.player_visual_intensity))
                Text(
                    stringResource(
                        R.string.player_visual_intensity_value,
                        (normalized.intensity * 100f).roundToInt(),
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = normalized.intensity,
                onValueChange = { value ->
                    onPreferencesChanged(normalized.copy(intensity = value).normalized())
                },
                valueRange = PlayerVisualEffectsPreferences.MIN_INTENSITY..
                    PlayerVisualEffectsPreferences.MAX_INTENSITY,
                modifier = Modifier.testTag(PlayerVisualTestTags.INTENSITY),
            )
            VisualSettingSwitch(
                label = stringResource(R.string.player_visual_follow_artwork),
                checked = normalized.followArtworkColors,
                tag = PlayerVisualTestTags.FOLLOW_ARTWORK,
                onCheckedChange = { follow ->
                    onPreferencesChanged(normalized.copy(followArtworkColors = follow))
                },
            )
            VisualSettingSwitch(
                label = stringResource(R.string.player_visual_audio_reactive),
                checked = normalized.audioReactive,
                tag = PlayerVisualTestTags.AUDIO_REACTIVE,
                onCheckedChange = { reactive ->
                    onPreferencesChanged(normalized.copy(audioReactive = reactive))
                },
            )
            if (normalized.enabled &&
                normalized.preset == PlayerVisualPreset.SPECTRUM &&
                normalized.audioReactive
            ) {
                when (audioReactiveAvailable) {
                    false -> Text(
                        text = stringResource(R.string.player_visual_audio_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(PlayerVisualTestTags.AUDIO_UNAVAILABLE),
                    )
                    null -> Text(
                        text = stringResource(
                            if (onRequestAudioAccess == null) {
                                R.string.player_visual_audio_checked_during_playback
                            } else {
                                R.string.player_visual_audio_starting
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    true -> Unit
                }
                if (onRequestAudioAccess != null) {
                    Text(
                        text = stringResource(R.string.player_visual_audio_privacy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (showAudioAccessAction && audioReactiveAvailable != true) {
                        TextButton(
                            onClick = onRequestAudioAccess,
                            modifier = Modifier.testTag(PlayerVisualTestTags.AUDIO_PERMISSION_ACTION),
                        ) {
                            Text(
                                stringResource(
                                    if (audioAccessActionIsRetry) {
                                        R.string.player_visual_audio_retry
                                    } else {
                                        R.string.player_visual_audio_enable_access
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.player_visual_performance),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VisualPerformanceMode.entries.take(2).forEach { mode ->
                    PerformanceChip(
                        mode = mode,
                        selected = normalized.performanceMode == mode,
                        chrome = chrome,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onPreferencesChanged(normalized.copy(performanceMode = mode))
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VisualPerformanceMode.entries.drop(2).forEach { mode ->
                    PerformanceChip(
                        mode = mode,
                        selected = normalized.performanceMode == mode,
                        chrome = chrome,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onPreferencesChanged(normalized.copy(performanceMode = mode))
                        },
                    )
                }
            }

            TextButton(
                onClick = onRestoreDefaults,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag(PlayerVisualTestTags.RESTORE_DEFAULTS),
            ) {
                Text(stringResource(R.string.player_visual_restore_defaults))
            }
        }
    }
}

@Composable
private fun PresetOption(
    title: String,
    description: String,
    selected: Boolean,
    tag: String,
    chrome: PlayerVisualPalette,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) chrome.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
        border = if (selected) BorderStroke(1.dp, chrome.accent) else null,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VisualSettingSwitch(
    label: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun PerformanceChip(
    mode: VisualPerformanceMode,
    selected: Boolean,
    chrome: PlayerVisualPalette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(visualPerformanceModeName(mode)) },
        modifier = modifier.testTag(PlayerVisualTestTags.performance(mode)),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = chrome.accent,
            selectedLabelColor = chrome.onAccent,
        ),
    )
}

@Composable
private fun presetDescription(preset: PlayerVisualPreset): String = when (preset) {
    PlayerVisualPreset.AURORA -> stringResource(R.string.player_visual_preset_aurora_description)
    PlayerVisualPreset.LIQUID -> stringResource(R.string.player_visual_preset_liquid_description)
    PlayerVisualPreset.SPECTRUM -> stringResource(R.string.player_visual_preset_spectrum_description)
}
