package com.mediahub.feature.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.mediahub.player.engine.TrackSelection
import kotlin.math.roundToLong

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 播放页：PlayerView + 自定义控制层。 */
@Composable
fun PlayerRoute(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resolve by viewModel.resolveState.collectAsStateWithLifecycle()
    val engineState by viewModel.engine.uiState.collectAsStateWithLifecycle()

    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 视频画面
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = viewModel.engine.exoPlayer
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        when (resolve) {
            ResolveState.Resolving -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }

            is ResolveState.Failed -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        (resolve as ResolveState.Failed).message,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Row(modifier = Modifier.padding(top = 16.dp)) {
                        TextButton(onClick = viewModel::resolve) { Text("重试", color = Color.White) }
                        TextButton(onClick = onBack) { Text("返回", color = Color.White) }
                    }
                }
            }

            ResolveState.Ready -> {
                PlayerControls(
                    state = state,
                    onBack = onBack,
                    onTogglePlayPause = viewModel.engine::togglePlayPause,
                    onSeek = viewModel.engine::seekTo,
                    onCycleSpeed = {
                        val next = SPEEDS[(SPEEDS.indexOf(state.speed) + 1).let { if (it >= SPEEDS.size) 0 else it }]
                        viewModel.engine.setSpeed(next)
                    },
                    onShowAudio = { showAudioDialog = true },
                    onShowSubtitle = { showSubtitleDialog = true },
                )
            }
        }
    }

    if (showAudioDialog) {
        TrackSelectionDialog(
            title = "音轨",
            items = engineState.audioTracks.map { it.title ?: "音轨 ${it.index + 1}" },
            selectedIndex = engineState.selectedAudio?.groupIndex,
            onSelect = { index ->
                viewModel.engine.selectAudioTrack(TrackSelection(index, 0))
                showAudioDialog = false
            },
            onDismiss = { showAudioDialog = false },
        )
    }
    if (showSubtitleDialog) {
        TrackSelectionDialog(
            title = "字幕",
            items = engineState.subtitleTracks.map { it.title ?: "字幕 ${it.index + 1}" },
            selectedIndex = engineState.selectedSubtitle?.groupIndex,
            onSelect = { index ->
                viewModel.engine.selectSubtitleTrack(TrackSelection(index, 0))
                showSubtitleDialog = false
            },
            onDismiss = { showSubtitleDialog = false },
        )
    }
}

@Composable
private fun PlayerControls(
    state: PlayerCombinedState,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onShowAudio: () -> Unit,
    onShowSubtitle: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(
                    text = state.mediaTitle ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onShowAudio) { Text("音轨", color = Color.White) }
                TextButton(onClick = onShowSubtitle) { Text("字幕", color = Color.White) }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Slider(
                value = state.positionMs.toFloat().coerceIn(0f, state.durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { onSeek(it.roundToLong()) },
                valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 12.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = onTogglePlayPause) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            tint = Color.White,
                        )
                    }
                    TextButton(onClick = onCycleSpeed) {
                        Text("%.2fx".format(state.speed), color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackSelectionDialog(
    title: String,
    items: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                items.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = { onSelect(index) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
