package com.mediahub.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.UserPreferences

/** 设置：播放偏好（DataStore 持久化）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
            Text("播放", style = MaterialTheme.typography.titleMedium)

            // 播放内核（U3-A）：默认 AUTO（Media3 快速路径，失败自动切 mpv 兼容内核）
            Text("播放内核", style = MaterialTheme.typography.bodyLarge)
            EngineModeSelector(
                mode = prefs.playbackEngineMode,
                onSelect = { mode ->
                    viewModel.update { p -> p.copy(playbackEngineMode = mode) }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSlider(
                label = "默认倍速",
                value = prefs.defaultPlaybackSpeed,
                valueText = "%.2fx".format(prefs.defaultPlaybackSpeed),
                range = 0.25f..2.0f,
                onValueChange = { newValue ->
                    viewModel.update { p -> p.copy(defaultPlaybackSpeed = newValue) }
                },
            )

            SettingSlider(
                label = "字幕大小",
                value = prefs.subtitleSizeSp.toFloat(),
                valueText = "${prefs.subtitleSizeSp} sp",
                range = 12f..32f,
                onValueChange = { newValue ->
                    viewModel.update { p -> p.copy(subtitleSizeSp = newValue.roundToInt()) }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitch(
                label = "优先硬件解码",
                checked = prefs.enableHardwareDecoding,
                onCheckedChange = { viewModel.update { p -> p.copy(enableHardwareDecoding = it) } },
            )
            SettingSwitch(
                label = "优先直连播放（Direct Play）",
                checked = prefs.preferDirectPlay,
                onCheckedChange = { viewModel.update { p -> p.copy(preferDirectPlay = it) } },
            )
            SettingSwitch(
                label = "自动连播下一集",
                checked = prefs.autoPlayNextEpisode,
                onCheckedChange = { viewModel.update { p -> p.copy(autoPlayNextEpisode = it) } },
            )
            SettingSwitch(
                label = "播放器显示媒体信息浮层",
                checked = prefs.showPlayerInfoOverlay,
                onCheckedChange = { viewModel.update { p -> p.copy(showPlayerInfoOverlay = it) } },
            )
            SettingSwitch(
                label = "播放视频时自动横屏",
                checked = prefs.autoLandscape,
                onCheckedChange = { viewModel.update { p -> p.copy(autoLandscape = it) } },
            )
            SettingSwitch(
                label = "播放时隐藏状态栏和导航栏",
                checked = prefs.immersiveBars,
                onCheckedChange = { viewModel.update { p -> p.copy(immersiveBars = it) } },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("关于", style = MaterialTheme.typography.titleMedium)
            Text(
                "MediaHub · Phase 0 骨架版本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EngineModeSelector(
    mode: PlaybackEngineMode,
    onSelect: (PlaybackEngineMode) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        EngineModeOption("自动", "兼容性自动选择", PlaybackEngineMode.AUTO, mode, onSelect, Modifier.weight(1.2f))
        EngineModeOption("Media3", "快速路径", PlaybackEngineMode.MEDIA3, mode, onSelect, Modifier.weight(1f))
        EngineModeOption("mpv", "兼容内核", PlaybackEngineMode.MPV, mode, onSelect, Modifier.weight(1f))
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
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
