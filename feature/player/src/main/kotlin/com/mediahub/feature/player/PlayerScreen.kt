package com.mediahub.feature.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleStyle
import com.mediahub.model.UserPreferences
import com.mediahub.model.SubtitleTrack
import com.mediahub.player.engine.TrackSelection
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

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
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    // 兜底（系统返回手势/组合销毁）：异步 final flush；正常返回按钮走同步 stopAndFlush（ADR-023）。
    DisposableEffect(Unit) {
        onDispose { viewModel.stopAndFlushAsync() }
    }

    // 自动横屏 + 沉浸式系统栏（Phase Player UX）：进入保存原方向并应用，退出恢复。
    // 生命周期兜底（DisposableEffect onDispose），不依赖"正常返回按钮"单一路径。
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, preferences.autoLandscape, preferences.immersiveBars) {
        val controller = activity?.let { PlayerSystemUiController(it) }
        controller?.enterPlayback(preferences.autoLandscape, preferences.immersiveBars)
        onDispose { controller?.exitPlayback() }
    }

    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 返回按钮：先完成进度 final flush（含远端短超时），再返回（ADR-023）
    val exitPlayer: () -> Unit = {
        scope.launch {
            viewModel.stopAndFlush()
            onBack()
        }
    }

    // review P2-8：系统 Back / predictive back 也必须经过 awaited stopAndFlush（不能绕过 final flush）
    BackHandler(enabled = true) {
        exitPlayer()
    }

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
            update = { view -> applySubtitleStyle(view.subtitleView, preferences) },
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
                        TextButton(onClick = exitPlayer) { Text("返回", color = Color.White) }
                    }
                }
            }

            ResolveState.Ready -> {
                PlayerControls(
                    state = state,
                    onBack = exitPlayer,
                    onTogglePlayPause = viewModel.engine::togglePlayPause,
                    onSeek = viewModel.engine::seekTo,
                    onCycleSpeed = {
                        val next = SPEEDS[(SPEEDS.indexOf(state.speed) + 1).let { if (it >= SPEEDS.size) 0 else it }]
                        viewModel.engine.setSpeed(next)
                    },
                    onShowAudio = { showAudioDialog = true },
                    onShowSubtitle = { showSubtitleDialog = true },
                )

                // 有画面但无音频输出 → 显式提示，不再"安静地没有声音"（Phase 1B-2.4）。
                // 判据用 Media3 audioFormat（真实输出信号），不用 isTrackSupported
                // （DTS-HD 等轨道标不支持，但设备解码器（如 c2.qti.dts.decoder）实际可解）。
                val audioUnsupported = engineState.audioTracks.isNotEmpty() &&
                    engineState.audioFormatMime == null && !engineState.isBuffering
                // 播放期错误（Source error 等）：resolve 已成功但引擎报错，显式展示（Phase 1B-2.4）
                state.error?.let { e ->
                    Text(
                        "播放失败：" + (e.message ?: "未知错误"),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (audioUnsupported) {
                    val codec = engineState.audioTracks.firstNotNullOfOrNull { prettyCodecName(it.codec) }
                    Text(
                        "未检测到音频输出" + (codec?.let { "（音轨 $it）" } ?: "") + "，该格式可能不被当前设备支持，可尝试其他音轨",
                        color = Color.Yellow,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 64.dp)
                            .padding(horizontal = 24.dp)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    if (showAudioDialog) {
        AudioTrackSheet(
            tracks = engineState.audioTracks,
            onDismiss = { showAudioDialog = false },
            onSelect = { track: AudioTrack ->
                viewModel.engine.selectAudioTrack(TrackSelection(track.index, 0))
                showAudioDialog = false
            },
        )
    }
    if (showSubtitleDialog) {
        SubtitleSheet(
            tracks = engineState.subtitleTracks,
            style = preferences.subtitleStyle,
            onDismiss = { showSubtitleDialog = false },
            onSelect = { track: SubtitleTrack? ->
                viewModel.engine.selectSubtitleTrack(track?.let { TrackSelection(it.index, 0) })
            },
            onStyleChange = { newStyle -> viewModel.updateSubtitleStyle { newStyle } },
        )
    }
}

/**
 * 字幕样式应用到 Media3 SubtitleView（默认白字 + 全透明背景 + 黑描边，ADR-032）。
 * 字号用视高比例：默认 18sp 档位 ≈ 0.0533 视高，textScale 相对该基准缩放。
 */
private fun applySubtitleStyle(subtitleView: androidx.media3.ui.SubtitleView?, prefs: UserPreferences) {
    subtitleView ?: return
    val style = prefs.subtitleStyle
    val edgeType = when (style.edgeType) {
        SubtitleStyle.EDGE_TYPE_OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleStyle.EDGE_TYPE_DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        else -> CaptionStyleCompat.EDGE_TYPE_NONE
    }
    subtitleView.setStyle(
        CaptionStyleCompat(
            style.textColor,
            style.backgroundColor,
            android.graphics.Color.TRANSPARENT,
            edgeType,
            style.edgeColor,
            null,
        ),
    )
    // 字号 = 基础视高比例 ×（设置页 18sp 基准档位）×（播放器 Bottom Sheet 缩放）
    val baseScale = (prefs.subtitleSizeSp / 18f).coerceIn(0.5f, 3f)
    subtitleView.setFractionalTextSize(BASE_SUBTITLE_FRACTION * baseScale * style.textScale)
    subtitleView.setBottomPaddingFraction(style.bottomPaddingFraction)
    subtitleView.setApplyEmbeddedStyles(style.applyEmbeddedStyles)
}

private const val BASE_SUBTITLE_FRACTION = 0.0533f

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
            if (state.durationMs > 0) {
                Slider(
                    value = state.positionMs.toFloat().coerceIn(0f, state.durationMs.toFloat()),
                    onValueChange = { onSeek(it.roundToLong()) },
                    valueRange = 0f..state.durationMs.toFloat(),
                )
            } else {
                // 时长未知（无临时时长且 Media3 timeline 未就绪）：禁用进度条，绝不拿 1ms 画满条
                Slider(
                    value = 0f,
                    onValueChange = {},
                    valueRange = 0f..1f,
                    enabled = false,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (state.durationMs > 0) {
                        formatTime(state.positionMs) + " / " + formatTime(state.durationMs)
                    } else {
                        formatTime(state.positionMs) + " / --:--"
                    },
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

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
