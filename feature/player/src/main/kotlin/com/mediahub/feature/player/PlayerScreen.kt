package com.mediahub.feature.player

import android.content.Context
import android.os.BatteryManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.ui.SubtitleView
import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleStyle
import com.mediahub.model.UserPreferences
import com.mediahub.model.SubtitleTrack
import com.mediahub.feature.player.gesture.GestureSeekPreview
import com.mediahub.feature.player.gesture.GestureSpeedPreview
import com.mediahub.feature.player.gesture.PlayerGestureController
import com.mediahub.feature.player.gesture.PlayerGestureLayer
import com.mediahub.player.engine.SeekMode
import com.mediahub.player.engine.TrackSelection
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
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
    val engineSwitching by viewModel.engineSwitching.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val serverDisplayName by viewModel.serverDisplayName.collectAsStateWithLifecycle()
    val serverIcon by viewModel.serverIcon.collectAsStateWithLifecycle()
    val downloadSpeedBps by viewModel.engine.downloadSpeedBps.collectAsStateWithLifecycle()
    val subtitleCues by viewModel.engine.subtitleCues.collectAsStateWithLifecycle()

    // 兜底（系统返回手势/组合销毁）：异步 final flush；正常返回按钮走同步 stopAndFlush（ADR-023）。
    DisposableEffect(Unit) {
        onDispose { viewModel.stopAndFlushAsync() }
    }

    // 自动横屏 + 沉浸式系统栏（Phase Player UX）：进入保存原方向并应用，退出恢复。
    // 偏好异步加载（null=未加载），加载完成后 apply，避免主线程 runBlocking 读 DataStore。
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val sysUiPrefs by viewModel.playerSystemUiPrefs.collectAsStateWithLifecycle()
    val sysUiController = remember(activity) { activity?.let { PlayerSystemUiController(it) } }
    LaunchedEffect(sysUiController, sysUiPrefs) {
        val prefs = sysUiPrefs ?: return@LaunchedEffect
        sysUiController?.enterPlayback(prefs.autoLandscape, prefs.immersiveBars)
    }
    DisposableEffect(sysUiController) {
        onDispose { sysUiController?.exitPlayback() }
    }

    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Overlay：点击切换显示 / 播放中 3s 自动隐藏（Item 6）
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(OVERLAY_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // 手势层（U3-B）：统一手势状态机驱动 Overlay / 双击矩阵 / scrub / 连续快退 / 长按倍速。
    // 闭包经属性代理读取最新 state/preferences（remember 不需要 keys）。
    val gestureController = remember {
        val engine = viewModel.engine
        PlayerGestureController(
            gestures = { preferences.gestures },
            positionMs = { state.positionMs },
            durationMs = { state.durationMs },
            currentSpeed = { state.speed },
            actions = object : PlayerGestureController.Actions {
                override fun onOverlayToggle() {
                    controlsVisible = !controlsVisible
                }

                override fun onPlayPauseToggle() {
                    engine.togglePlayPause()
                }

                override fun onPreviewSeek(positionMs: Long) {
                    engine.seekTo(positionMs, SeekMode.PREVIEW)
                }

                override fun onCommitSeek(positionMs: Long) {
                    engine.seekTo(positionMs, SeekMode.COMMIT)
                }

                override fun onSpeedChange(speed: Float) {
                    engine.setSpeed(speed)
                }
            },
        )
    }
    val scrubPreview by gestureController.scrubPreview.collectAsStateWithLifecycle()
    val speedPreview by gestureController.speedPreview.collectAsStateWithLifecycle()

    // 设备电量（Item 10）：进入即读，每 60s 刷新
    var batteryLevel by remember { mutableStateOf(readBatteryLevel(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            batteryLevel = readBatteryLevel(context)
        }
    }

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
        // 视频渲染 Surface（Media3/mpv 统一走 attachSurface）
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            viewModel.engine.attachSurface(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.engine.attachSurface(null)
                        }
                    })
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 字幕 overlay（Media3 独立 SubtitleView；mpv 内部 libass 渲染，subtitleCues=null 时不显示）
        AndroidView(
            factory = { context ->
                SubtitleView(context).apply {
                    applySubtitleStyle(this, preferences)
                }
            },
            update = { view ->
                applySubtitleStyle(view, preferences)
                view.setCues(subtitleCues?.cues)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层（U3-B）：单击 Overlay / 双击矩阵 / 水平 scrub / 双击按住快退 / 长按倍速
        PlayerGestureLayer(
            controller = gestureController,
            modifier = Modifier.fillMaxSize(),
        )

        // 手势预览指示（U3-B）：scrub / 连续快退 / 长按倍速
        scrubPreview?.let { GestureSeekIndicator(it, Modifier.align(Alignment.Center)) }
        speedPreview?.let { GestureSpeedIndicator(it, Modifier.align(Alignment.Center)) }

        // 引擎自动降级提示（U3-A：Media3 失败 → mpv 同位置重播）
        if (engineSwitching) {
            Text(
                "正在切换兼容播放模式…",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

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
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    PlayerControls(
                        state = state,
                        serverDisplayName = serverDisplayName,
                        serverIcon = serverIcon,
                        downloadSpeedBps = downloadSpeedBps,
                        batteryLevel = batteryLevel,
                        onBack = exitPlayer,
                        onTogglePlayPause = viewModel.engine::togglePlayPause,
                        onSeek = { viewModel.engine.seekTo(it) },
                        onCycleSpeed = {
                            val next = SPEEDS[(SPEEDS.indexOf(state.speed) + 1).let { if (it >= SPEEDS.size) 0 else it }]
                            viewModel.engine.setSpeed(next)
                        },
                        onShowAudio = { showAudioDialog = true },
                        onShowSubtitle = { showSubtitleDialog = true },
                    )
                }

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
    serverDisplayName: String?,
    serverIcon: String?,
    downloadSpeedBps: Long,
    batteryLevel: Int?,
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
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
                Text(
                    text = state.mediaTitle ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onShowAudio) { Text("音轨", color = Color.White) }
                TextButton(onClick = onShowSubtitle) { Text("字幕", color = Color.White) }
            }
            if (serverDisplayName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    if (!serverIcon.isNullOrBlank()) {
                        Text(
                            text = serverIcon,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = serverDisplayName,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatSpeed(downloadSpeedBps),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
                batteryLevel?.let {
                    Text(
                        text = "电量 $it%",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.durationMs > 0) {
                // 进度条 preview→commit（U3-B）：拖动只更新本地预览值，松手才真正 seek
                var sliderScrubValue by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = sliderScrubValue
                        ?: state.positionMs.toFloat().coerceIn(0f, state.durationMs.toFloat()),
                    onValueChange = { sliderScrubValue = it },
                    onValueChangeFinished = {
                        sliderScrubValue?.let { onSeek(it.roundToLong()) }
                        sliderScrubValue = null
                    },
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

/**
 * 手势 seek 预览指示（scrub / 连续快退共用，U3-B）：
 * 居中显示 delta（带符号）与目标时间，松手后消失。
 */
@Composable
private fun GestureSeekIndicator(preview: GestureSeekPreview, modifier: Modifier = Modifier) {
    val sign = if (preview.deltaMs >= 0) "+" else "-"
    val absSeconds = kotlin.math.abs(preview.deltaMs) / 1000
    val deltaText = "$sign%d:%02d".format(absSeconds / 60, absSeconds % 60)
    Text(
        text = "$deltaText  →  ${formatTime(preview.targetPositionMs)}",
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** 长按临时倍速/rewind 指示（U3-B revision）：居中显示当前档位。 */
@Composable
private fun GestureSpeedIndicator(preview: GestureSpeedPreview, modifier: Modifier = Modifier) {
    val text = if (preview.isRewind) "↺ %.2f× 快退中".format(preview.speed) else "%.2f× 倍速中".format(preview.speed)
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0) return "--"
    val kb = bytesPerSecond / 1024.0
    return if (kb < 1024) "%.0f KB/s".format(kb) else "%.1f MB/s".format(kb / 1024.0)
}

private fun readBatteryLevel(context: Context): Int? {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return level.takeIf { it in 0..100 }
}

private const val OVERLAY_AUTO_HIDE_MS = 3_000L
