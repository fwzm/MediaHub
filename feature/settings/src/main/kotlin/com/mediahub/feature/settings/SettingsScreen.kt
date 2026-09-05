package com.mediahub.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.mediahub.core.ui.R as CoreUiR
import com.mediahub.core.ui.effects.PlayerVisualSettingsPanel
import com.mediahub.core.ui.effects.PlayerVisualPalette
import com.mediahub.core.ui.effects.PlayerVisualPresetMapper
import com.mediahub.core.ui.effects.PlayerVisualTestTags
import com.mediahub.core.ui.effects.playerVisualPresetName
import com.mediahub.core.ui.effects.visualPerformanceModeName
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.UserPreferences

/** 设置：播放偏好（DataStore 持久化）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    visualPreviewRunningOverride: Boolean? = null,
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    var showVisualEffectsSettings by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_playback), style = MaterialTheme.typography.titleMedium)

            // 播放内核（U3-A）：默认 AUTO（Media3 快速路径，失败自动切 mpv 兼容内核）
            Text(stringResource(R.string.settings_playback_engine), style = MaterialTheme.typography.bodyLarge)
            EngineModeSelector(
                mode = prefs.playbackEngineMode,
                onSelect = { mode ->
                    viewModel.update { p -> p.copy(playbackEngineMode = mode) }
                },
            )

            VisualEffectsSettingsEntry(
                prefs = prefs,
                onClick = { showVisualEffectsSettings = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSlider(
                label = stringResource(R.string.settings_default_speed),
                value = prefs.defaultPlaybackSpeed,
                valueText = stringResource(R.string.settings_speed_two_decimals, prefs.defaultPlaybackSpeed),
                range = 0.25f..2.0f,
                onValueChange = { newValue ->
                    viewModel.update { p -> p.copy(defaultPlaybackSpeed = newValue) }
                },
            )

            SettingSlider(
                label = stringResource(R.string.settings_subtitle_size),
                value = prefs.subtitleSizeSp.toFloat(),
                valueText = stringResource(R.string.settings_subtitle_size_value, prefs.subtitleSizeSp),
                range = 12f..32f,
                onValueChange = { newValue ->
                    viewModel.update { p -> p.copy(subtitleSizeSp = newValue.roundToInt()) }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitch(
                label = stringResource(R.string.settings_prefer_hardware_decoding),
                checked = prefs.enableHardwareDecoding,
                onCheckedChange = { viewModel.update { p -> p.copy(enableHardwareDecoding = it) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_prefer_direct_play),
                checked = prefs.preferDirectPlay,
                onCheckedChange = { viewModel.update { p -> p.copy(preferDirectPlay = it) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_auto_play_next_episode),
                checked = prefs.autoPlayNextEpisode,
                onCheckedChange = { viewModel.update { p -> p.copy(autoPlayNextEpisode = it) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_show_media_info),
                checked = prefs.showPlayerInfoOverlay,
                onCheckedChange = { viewModel.update { p -> p.copy(showPlayerInfoOverlay = it) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_auto_landscape),
                checked = prefs.autoLandscape,
                onCheckedChange = { viewModel.update { p -> p.copy(autoLandscape = it) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_immersive_bars),
                checked = prefs.immersiveBars,
                onCheckedChange = { viewModel.update { p -> p.copy(immersiveBars = it) } },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 播放器手势（U3-B）：独立于默认倍速的 9 项手势偏好
            Text(stringResource(R.string.settings_player_gestures), style = MaterialTheme.typography.titleMedium)

            SettingSwitch(
                label = stringResource(R.string.settings_gesture_scrub),
                checked = prefs.gestures.scrubEnabled,
                onCheckedChange = { viewModel.update { p -> p.copy(gestures = p.gestures.copy(scrubEnabled = it)) } },
            )

            SettingSwitch(
                label = stringResource(R.string.settings_gesture_double_tap_rewind),
                checked = prefs.gestures.doubleTapSeekBackwardEnabled,
                onCheckedChange = { viewModel.update { p -> p.copy(gestures = p.gestures.copy(doubleTapSeekBackwardEnabled = it)) } },
            )
            if (prefs.gestures.doubleTapSeekBackwardEnabled) {
                SettingSlider(
                    label = stringResource(R.string.settings_gesture_rewind_seconds),
                    value = prefs.gestures.doubleTapSeekBackwardSeconds.toFloat(),
                    valueText = stringResource(R.string.settings_seconds_value, prefs.gestures.doubleTapSeekBackwardSeconds),
                    range = 5f..60f,
                    onValueChange = { newValue ->
                        viewModel.update { p ->
                            p.copy(gestures = p.gestures.copy(doubleTapSeekBackwardSeconds = newValue.roundToInt()))
                        }
                    },
                )
            }

            SettingSwitch(
                label = stringResource(R.string.settings_gesture_double_tap_forward),
                checked = prefs.gestures.doubleTapSeekForwardEnabled,
                onCheckedChange = { viewModel.update { p -> p.copy(gestures = p.gestures.copy(doubleTapSeekForwardEnabled = it)) } },
            )
            if (prefs.gestures.doubleTapSeekForwardEnabled) {
                SettingSlider(
                    label = stringResource(R.string.settings_gesture_forward_seconds),
                    value = prefs.gestures.doubleTapSeekForwardSeconds.toFloat(),
                    valueText = stringResource(R.string.settings_seconds_value, prefs.gestures.doubleTapSeekForwardSeconds),
                    range = 5f..60f,
                    onValueChange = { newValue ->
                        viewModel.update { p ->
                            p.copy(gestures = p.gestures.copy(doubleTapSeekForwardSeconds = newValue.roundToInt()))
                        }
                    },
                )
            }

            SettingSwitch(
                label = stringResource(R.string.settings_gesture_long_press_speed),
                checked = prefs.gestures.longPressSpeedEnabled,
                onCheckedChange = { viewModel.update { p -> p.copy(gestures = p.gestures.copy(longPressSpeedEnabled = it)) } },
            )
            if (prefs.gestures.longPressSpeedEnabled) {
                SettingSwitch(
                    label = stringResource(R.string.settings_gesture_long_press_directional),
                    checked = prefs.gestures.longPressDirectionalEnabled,
                    onCheckedChange = { viewModel.update { p -> p.copy(gestures = p.gestures.copy(longPressDirectionalEnabled = it)) } },
                )
                SettingSlider(
                    label = stringResource(R.string.settings_gesture_default_long_press_speed),
                    value = prefs.gestures.longPressDefaultSpeed,
                    valueText = stringResource(R.string.settings_speed_one_decimal, prefs.gestures.longPressDefaultSpeed),
                    range = 1f..4f,
                    onValueChange = { newValue ->
                        viewModel.update { p -> p.copy(gestures = p.gestures.copy(longPressDefaultSpeed = newValue)) }
                    },
                )
                Text(stringResource(R.string.settings_gesture_min_speed), style = MaterialTheme.typography.bodyLarge)
                Row(modifier = Modifier.fillMaxWidth()) {
                    SpeedMinOption(
                        label = stringResource(R.string.settings_speed_one_decimal, 0.5f),
                        description = stringResource(R.string.settings_gesture_regular_slow_motion),
                        value = 0.5f,
                        current = prefs.gestures.longPressSpeedMin,
                        onSelect = { v ->
                            viewModel.update { p -> p.copy(gestures = p.gestures.copy(longPressSpeedMin = v)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SpeedMinOption(
                        label = stringResource(R.string.settings_speed_one_decimal, 0.1f),
                        description = stringResource(R.string.settings_gesture_frame_level_slow_motion),
                        value = 0.1f,
                        current = prefs.gestures.longPressSpeedMin,
                        onSelect = { v ->
                            viewModel.update { p -> p.copy(gestures = p.gestures.copy(longPressSpeedMin = v)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            AboutSection()
        }
    }

    if (showVisualEffectsSettings) {
        val visualPreferences = prefs.playerVisualEffects.normalized()
        val visualSourcePalette = remember(visualPreferences.preset, visualPreferences.intensity) {
            PlayerVisualPresetMapper.resolve(
                preset = visualPreferences.preset,
                intensity = visualPreferences.intensity,
                targetFps = 30,
            ).palette
        }
        val visualChrome = remember(visualSourcePalette) {
            PlayerVisualPalette.from(visualSourcePalette)
        }
        ModalBottomSheet(
            onDismissRequest = { showVisualEffectsSettings = false },
            containerColor = visualChrome.surface,
            contentColor = visualChrome.onSurface,
            dragHandle = {
                BottomSheetDefaults.DragHandle(color = visualChrome.onSurfaceVariant)
            },
        ) {
            PlayerVisualSettingsPanel(
                preferences = visualPreferences,
                onPreferencesChanged = { visual ->
                    viewModel.update { current -> current.copy(playerVisualEffects = visual) }
                },
                onRestoreDefaults = viewModel::resetPlayerVisualEffects,
                audioReactiveAvailable = null,
                previewRunning = visualPreviewRunningOverride
                    ?: lifecycleState.isAtLeast(Lifecycle.State.STARTED),
            )
        }
    }
}

@Composable
internal fun VisualEffectsSettingsEntry(
    prefs: UserPreferences,
    onClick: () -> Unit,
) {
    val visual = prefs.playerVisualEffects
    val summary = if (!visual.enabled) {
        stringResource(CoreUiR.string.player_visual_effects_summary_off)
    } else {
        stringResource(
            CoreUiR.string.player_visual_effects_summary_format,
            playerVisualPresetName(visual.preset),
            visualPerformanceModeName(visual.performanceMode),
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(PlayerVisualTestTags.SETTINGS_ENTRY),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(CoreUiR.string.player_visual_effects_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun EngineModeSelector(
    mode: PlaybackEngineMode,
    onSelect: (PlaybackEngineMode) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        EngineModeOption(
            stringResource(R.string.settings_engine_auto),
            stringResource(R.string.settings_engine_auto_description),
            PlaybackEngineMode.AUTO, mode, onSelect, Modifier.weight(1.2f),
        )
        EngineModeOption(
            stringResource(R.string.settings_engine_media3),
            stringResource(R.string.settings_engine_media3_description),
            PlaybackEngineMode.MEDIA3, mode, onSelect, Modifier.weight(1f),
        )
        EngineModeOption(
            stringResource(R.string.settings_engine_mpv),
            stringResource(R.string.settings_engine_mpv_description),
            PlaybackEngineMode.MPV, mode, onSelect, Modifier.weight(1f),
        )
    }
}

@Composable
private fun EngineModeOption(
    label: String,
    description: String,
    value: PlaybackEngineMode,
    current: PlaybackEngineMode,
    onSelect: (PlaybackEngineMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = value == current,
                role = Role.RadioButton,
                onClick = { onSelect(value) },
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = value == current, onClick = null)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeedMinOption(
    label: String,
    description: String,
    value: Float,
    current: Float,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = value == current,
                role = Role.RadioButton,
                onClick = { onSelect(value) },
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = value == current, onClick = null)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private const val GITHUB_REPO = "https://github.com/fwzm/MediaHub"

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val versionName = remember {
        try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.settings_product_name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.settings_product_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // GitHub 链接
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(GITHUB_REPO) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Column {
                        Text(
                            stringResource(R.string.settings_github),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            GITHUB_REPO,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 反馈入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("$GITHUB_REPO/issues") }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Column {
                        Text(
                            stringResource(R.string.settings_feedback),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.settings_feedback_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
