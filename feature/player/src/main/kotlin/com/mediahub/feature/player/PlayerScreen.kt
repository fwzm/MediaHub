package com.mediahub.feature.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.view.WindowManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.mediahub.model.AudioTrack
import com.mediahub.model.PlayerVisualPreset
import com.mediahub.model.SubtitleStyle
import com.mediahub.model.UserPreferences
import com.mediahub.model.SubtitleTrack
import com.mediahub.feature.player.gesture.GestureSeekPreview
import com.mediahub.feature.player.gesture.GestureSpeedPreview
import com.mediahub.feature.player.gesture.PlayerGestureController
import com.mediahub.feature.player.gesture.GestureLevelPreview
import com.mediahub.feature.player.gesture.PlayerGestureLayer
import com.mediahub.feature.player.gesture.PlayerLevelKind
import com.mediahub.core.ui.effects.PlayerVisualOverlay
import com.mediahub.core.ui.effects.PlayerVisualMaskConfig
import com.mediahub.core.ui.effects.PlayerVisualTestTags
import com.mediahub.core.ui.effects.PlayerVisualChromeBackground
import com.mediahub.core.ui.effects.PlayerVisualRenderRequest
import com.mediahub.core.ui.effects.PlayerVisualRuntimePolicy
import com.mediahub.core.ui.effects.PlayerVisualTheme
import com.mediahub.core.ui.effects.RendererBackend
import com.mediahub.core.ui.effects.RendererBackendSelector
import com.mediahub.core.ui.effects.SpectrumFrame
import com.mediahub.core.ui.effects.SpectrumProvider
import com.mediahub.core.ui.effects.FlowGlowClock
import com.mediahub.core.ui.effects.rememberFlowGlowClock
import com.mediahub.core.ui.effects.rememberPowerSaveMode
import com.mediahub.core.ui.effects.rememberReduceMotion
import com.mediahub.player.engine.AudioBandLevels
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.SeekMode
import com.mediahub.player.engine.TrackSelection
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

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
    val storedPreferences by viewModel.preferences.collectAsStateWithLifecycle()
    val preferences = storedPreferences ?: UserPreferences()
    val serverDisplayName by viewModel.serverDisplayName.collectAsStateWithLifecycle()
    val serverIcon by viewModel.serverIcon.collectAsStateWithLifecycle()
    val downloadSpeedBps by viewModel.engine.downloadSpeedBps.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val subtitleCues by viewModel.engine.subtitleCues.collectAsStateWithLifecycle()
    val artworkPalette by viewModel.artworkPalette.collectAsStateWithLifecycle()
    val engineKind by viewModel.engineKind.collectAsStateWithLifecycle()
    val audioSpectrumBridge = rememberAudioSpectrumBridge(viewModel.engine.audioBands)

    // 兜底（系统返回手势/组合销毁）：异步 final flush；正常返回按钮走同步 stopAndFlush（ADR-023）。
    DisposableEffect(Unit) {
        onDispose { viewModel.stopAndFlushAsync() }
    }

    // 自动横屏 + 沉浸式系统栏（Phase Player UX）：进入保存原方向并应用，退出恢复。
    // 偏好异步加载（null=未加载），加载完成后 apply，避免主线程 runBlocking 读 DataStore。
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val lifecycleStarted = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val powerSaveMode = rememberPowerSaveMode(context)
    val reduceMotion = rememberReduceMotion(context)
    var recordAudioPermissionGranted by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var recordAudioPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var recordAudioPermissionRequestArmed by rememberSaveable { mutableStateOf(false) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        recordAudioPermissionGranted = granted
    }
    LaunchedEffect(lifecycleState) {
        recordAudioPermissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val activity = remember(context) { context.findActivity() }
    val requestRecordAudioAccess: () -> Unit = {
        when {
            recordAudioPermissionGranted -> viewModel.engine.retryAudioSpectrumCapture()
            recordAudioPermissionRequested || context.isRecordAudioPermanentlyDenied(activity) -> {
                context.openApplicationPermissionSettings()
            }
            else -> recordAudioPermissionRequestArmed = true
        }
    }
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
    var showVisualEffectsSettings by remember { mutableStateOf(false) }
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
    val levelPreview by gestureController.levelPreview.collectAsStateWithLifecycle()
    var forceVisualFallback by remember(viewModel) {
        mutableStateOf(Build.VERSION.SDK_INT < RendererBackendSelector.RUNTIME_SHADER_MIN_API)
    }
    var rendererBackend by remember(viewModel) {
        mutableStateOf(
            if (forceVisualFallback) RendererBackend.FALLBACK_GRADIENT else RendererBackend.NONE,
        )
    }
    val rendererPreferences = PlaybackVisualStateResolver.preferencesForRenderer(storedPreferences)
    val audioSpectrum = if (audioSpectrumBridge.available) SpectrumFrame.Zero else null
    val visualState = PlaybackVisualStateResolver.resolve(
        preferences = rendererPreferences,
        artworkPalette = artworkPalette,
        audioSpectrum = audioSpectrum,
        lifecycleStarted = lifecycleStarted,
        controlsVisible = controlsVisible || showVisualEffectsSettings,
        userInteracting = showVisualEffectsSettings ||
            scrubPreview != null || speedPreview != null || levelPreview != null,
        powerSave = powerSaveMode,
        reduceMotion = reduceMotion,
        rendererBackend = rendererBackend,
    )
    val spectrumProvider = audioSpectrumBridge.provider
    val sharedVisualClock = rememberFlowGlowClock(
        fps = visualState.renderRequest.frameDecision.targetFps.coerceAtLeast(1),
        running = PlayerVisualRuntimePolicy.shouldRunSharedClock(
            requestedRunning = visualState.renderRequest.frameDecision.running,
            forceFallback = forceVisualFallback,
        ),
    )
    val reportVisualBackend: (RendererBackend) -> Unit = { backend ->
        val nextForceFallback = PlayerVisualRuntimePolicy.latchForceFallback(
            currentForceFallback = forceVisualFallback,
            reportedBackend = backend,
        )
        forceVisualFallback = nextForceFallback
        rendererBackend = when {
            !visualState.renderRequest.frameDecision.running -> RendererBackend.NONE
            nextForceFallback -> RendererBackend.FALLBACK_GRADIENT
            else -> backend
        }
    }

    // 竖向亮度/音量状态（U3-C）
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    fun readVolumeFraction(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }
    DisposableEffect(Unit) {
        val originalBrightness = activity?.window?.attributes?.screenBrightness
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { 
                    screenBrightness = originalBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE 
                }
            }
        }
    }

    // 竖向亮度/音量实时应用（U3-C.1：拖动过程中即时生效，非松手才 apply）
    LaunchedEffect(levelPreview) {
        val preview = levelPreview ?: return@LaunchedEffect
        when (preview.kind) {
            PlayerLevelKind.BRIGHTNESS -> {
                activity?.window?.attributes = activity?.window?.attributes?.apply { 
                    screenBrightness = preview.fraction 
                }
            }
            PlayerLevelKind.VOLUME -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (preview.fraction * max).roundToInt(), 0)
            }
        }
    }

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

    PlayerVisualTheme(palette = visualState.chromePalette) {
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

        // 只画左右窄边与底部 controls chrome；中心视频和字幕安全区没有 shader 像素。
        PlayerVisualOverlay(
            request = visualState.renderRequest,
            modifier = Modifier.fillMaxSize(),
            spectrum = spectrumProvider,
            progressProvider = {
                if (state.durationMs > 0L) {
                    (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            includeBottomChrome = false,
            clock = sharedVisualClock,
            forceFallback = forceVisualFallback,
            onBackendChanged = reportVisualBackend,
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
            onBrightness = {
                val b = activity?.window?.attributes?.screenBrightness ?: -1f
                if (b >= 0f) b else readSystemBrightness(context)
            },
            onVolume = { readVolumeFraction() },
        )

        // 手势预览指示（U3-B）：scrub / 连续快退 / 长按倍速
        scrubPreview?.let { GestureSeekIndicator(it, Modifier.align(Alignment.Center)) }
        speedPreview?.let { GestureSpeedIndicator(it, Modifier.align(Alignment.Center)) }
        levelPreview?.let { preview ->
            val alignment = if (preview.kind == PlayerLevelKind.BRIGHTNESS) Alignment.CenterStart else Alignment.CenterEnd
            GestureLevelIndicator(preview, Modifier.align(alignment))
        }

        // 引擎自动降级提示（U3-A：Media3 失败 → mpv 同位置重播）
        if (engineSwitching) {
            Text(
                stringResource(R.string.player_switching_engine),
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
                        TextButton(onClick = viewModel::resolve) {
                            Text(stringResource(R.string.player_retry), color = Color.White)
                        }
                        TextButton(onClick = exitPlayer) {
                            Text(stringResource(R.string.player_back), color = Color.White)
                        }
                    }
                }
            }

            ResolveState.Ready -> {
                if (controlsVisible) {
                    PlayerControls(
                        modifier = Modifier.matchParentSize(),
                        state = state,
                        serverDisplayName = serverDisplayName,
                        serverIcon = serverIcon,
                        downloadSpeedBps = downloadSpeedBps,
                        batteryLevel = batteryLevel,
                        diagnostics = diagnostics,
                        visualRequest = visualState.renderRequest,
                        visualSpectrum = spectrumProvider,
                        visualClock = sharedVisualClock,
                        forceVisualFallback = forceVisualFallback,
                        onVisualBackendChanged = reportVisualBackend,
                        subtitleSelected = engineState.selectedSubtitle != null,
                        onBack = exitPlayer,
                        onTogglePlayPause = viewModel.engine::togglePlayPause,
                        onSeek = { viewModel.engine.seekTo(it) },
                        onCycleSpeed = {
                            val next = SPEEDS[(SPEEDS.indexOf(state.speed) + 1).let { if (it >= SPEEDS.size) 0 else it }]
                            viewModel.engine.setSpeed(next)
                        },
                        onShowVisualEffects = { showVisualEffectsSettings = true },
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
                        stringResource(
                            R.string.player_playback_failed,
                            e.message ?: stringResource(R.string.player_unknown_error),
                        ),
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
                        if (codec != null) {
                            stringResource(R.string.player_audio_unsupported_codec, codec)
                        } else {
                            stringResource(R.string.player_audio_unsupported)
                        },
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
    }
    val audioPermissionDecision = AudioSpectrumPermissionPolicy.resolve(
        AudioSpectrumPermissionInputs(
            preferences = storedPreferences?.playerVisualEffects,
            resolveReady = resolve is ResolveState.Ready,
            engineKind = engineKind,
            rendererBackend = rendererBackend,
            lifecycleStarted = lifecycleStarted,
            consumerVisible = controlsVisible || showVisualEffectsSettings,
            permissionGranted = recordAudioPermissionGranted,
            explicitRequestArmed = recordAudioPermissionRequestArmed,
            requestAttemptedThisRoute = recordAudioPermissionRequested,
        ),
    )
    val audioPreferenceWantsSpectrum = storedPreferences?.playerVisualEffects?.normalized()?.let { visual ->
        visual.enabled &&
            visual.preset == PlayerVisualPreset.SPECTRUM &&
            visual.audioReactive &&
            visual.intensity > 0f
    } == true
    LaunchedEffect(audioPreferenceWantsSpectrum) {
        if (!audioPreferenceWantsSpectrum) recordAudioPermissionRequestArmed = false
    }
    LaunchedEffect(audioPermissionDecision) {
        when {
            audioPermissionDecision.requestPermission -> {
                recordAudioPermissionRequested = true
                recordAudioPermissionRequestArmed = false
                context.markRecordAudioPermissionRequested()
                viewModel.engine.setAudioSpectrumEnabled(false)
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> viewModel.engine.setAudioSpectrumEnabled(audioPermissionDecision.captureEnabled)
        }
    }
    DisposableEffect(viewModel.engine) {
        onDispose { viewModel.engine.setAudioSpectrumEnabled(false) }
    }

    var audioSpectrumStartupTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(audioPermissionDecision.captureEnabled, audioSpectrumBridge.available) {
        audioSpectrumStartupTimedOut = false
        if (audioPermissionDecision.captureEnabled && !audioSpectrumBridge.available) {
            delay(AUDIO_SPECTRUM_START_TIMEOUT_MS)
            if (audioPermissionDecision.captureEnabled && !audioSpectrumBridge.available) {
                audioSpectrumStartupTimedOut = true
            }
        }
    }
    val audioReactiveAvailable: Boolean? = when {
        !audioPreferenceWantsSpectrum -> null
        audioSpectrumBridge.available -> true
        !recordAudioPermissionGranted -> false
        engineKind == EngineKind.MPV -> false
        rendererBackend == RendererBackend.FALLBACK_GRADIENT -> false
        audioSpectrumStartupTimedOut -> false
        else -> null
    }
    val showAudioAccessAction = audioPreferenceWantsSpectrum &&
        resolve is ResolveState.Ready &&
        engineKind == EngineKind.MEDIA3 &&
        rendererBackend == RendererBackend.RUNTIME_SHADER &&
        lifecycleStarted &&
        (controlsVisible || showVisualEffectsSettings) &&
        (!recordAudioPermissionGranted || audioSpectrumStartupTimedOut)

    if (showVisualEffectsSettings) {
        PlayerVisualEffectsSheet(
            preferences = preferences.playerVisualEffects,
            previewPalette = visualState.sourcePalette,
            spectrum = spectrumProvider,
            audioReactiveAvailable = audioReactiveAvailable,
            showAudioAccessAction = showAudioAccessAction,
            audioAccessActionIsRetry = recordAudioPermissionGranted,
            lifecycleStarted = lifecycleStarted,
            onPreferencesChanged = { next ->
                val previous = preferences.playerVisualEffects.normalized()
                val normalizedNext = next.normalized()
                val explicitlyEnabledSpectrum = normalizedNext.enabled &&
                    normalizedNext.preset == PlayerVisualPreset.SPECTRUM &&
                    normalizedNext.audioReactive &&
                    (!previous.enabled ||
                        previous.preset != PlayerVisualPreset.SPECTRUM ||
                        !previous.audioReactive)
                if (explicitlyEnabledSpectrum) requestRecordAudioAccess()
                viewModel.updatePlayerVisualEffects { next }
            },
            onRequestAudioAccess = requestRecordAudioAccess,
            onRestoreDefaults = viewModel::resetPlayerVisualEffects,
            onDismiss = { showVisualEffectsSettings = false },
        )
    }

    if (showAudioDialog) {
        PlayerVisualTheme(palette = visualState.chromePalette) {
            AudioTrackSheet(
                tracks = engineState.audioTracks,
                onDismiss = { showAudioDialog = false },
                onSelect = { track: AudioTrack ->
                    viewModel.engine.selectAudioTrack(TrackSelection(track.index, 0))
                    showAudioDialog = false
                },
            )
        }
    }
    if (showSubtitleDialog) {
        PlayerVisualTheme(palette = visualState.chromePalette) {
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
}

private fun AudioBandLevels.toSpectrumFrame(): SpectrumFrame = SpectrumFrame(
    bass = bass,
    mid = mid,
    treble = treble,
    amplitude = amplitude,
)

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
    modifier: Modifier = Modifier,
    state: PlayerCombinedState,
    serverDisplayName: String?,
    serverIcon: String?,
    downloadSpeedBps: Long,
    batteryLevel: Int?,
    diagnostics: PlaybackDiagnosticsState?,
    visualRequest: PlayerVisualRenderRequest,
    visualSpectrum: SpectrumProvider,
    visualClock: FlowGlowClock,
    forceVisualFallback: Boolean,
    onVisualBackendChanged: (RendererBackend) -> Unit,
    subtitleSelected: Boolean,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onShowVisualEffects: () -> Unit,
    onShowAudio: () -> Unit,
    onShowSubtitle: () -> Unit,
) {
    val seekBarColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        activeTickColor = MaterialTheme.colorScheme.onPrimary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        disabledActiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
        disabledActiveTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
        disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        disabledInactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
    )
    BoxWithConstraints(modifier = modifier.testTag(PlayerVisualTestTags.PLAYER_CONTROLS)) {
        // Use the full player height, not the tall controls content, to protect subtitle pixels.
        val maxAmbientHeight = maxHeight * (1f - PlayerVisualMaskConfig().bottomStart)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.player_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = state.mediaTitle ?: "",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                PlayerVisualEffectsEntry(onClick = onShowVisualEffects)
                TextButton(onClick = onShowAudio) {
                    Text(stringResource(R.string.player_audio_tracks), color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onShowSubtitle) {
                    Text(stringResource(R.string.player_subtitles), color = MaterialTheme.colorScheme.primary)
                }
            }
            if (serverDisplayName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    if (!serverIcon.isNullOrBlank()) {
                        Text(
                            text = serverIcon,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = serverDisplayName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
            // 播放诊断（U4-E）：引擎/协议/首包/缓冲
            if (diagnostics != null && diagnostics!!.engine != null) {
                val d = diagnostics!!
                val diagParts = buildList {
                    d.engine?.let { add(it) }
                    d.mediaProtocol?.let { add(it) }
                    d.httpStatus?.let { add(stringResource(R.string.player_diagnostic_http, it)) }
                    d.mediaFirstByteMs?.let { add(stringResource(R.string.player_diagnostic_first_byte, it)) }
                    if (d.bufferedMs > 0) {
                        add(stringResource(R.string.player_diagnostic_buffered, d.bufferedMs / 1000))
                    }
                }
                if (diagParts.isNotEmpty()) {
                    Text(
                        text = diagParts.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
        ) {
            PlayerVisualChromeBackground(
                request = visualRequest,
                maxAmbientHeight = maxAmbientHeight,
                spectrum = visualSpectrum,
                clock = visualClock,
                forceFallback = forceVisualFallback,
                onBackendChanged = onVisualBackendChanged,
                progressProvider = {
                    if (state.durationMs > 0L) {
                        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                scrimColor = MaterialTheme.colorScheme.surface,
                scrimTopAlpha = when {
                    !visualRequest.frameDecision.running -> 0.72f
                    subtitleSelected -> 0.48f
                    else -> 0.30f
                },
                scrimBottomAlpha = when {
                    !visualRequest.frameDecision.running -> 0.84f
                    subtitleSelected -> 0.78f
                    else -> 0.66f
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatSpeed(downloadSpeedBps),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        batteryLevel?.let {
                            Text(
                                text = stringResource(R.string.player_battery, it),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            colors = seekBarColors,
                        )
                    } else {
                        // 时长未知（无临时时长且 Media3 timeline 未就绪）：禁用进度条，绝不拿 1ms 画满条
                        Slider(
                            value = 0f,
                            onValueChange = {},
                            valueRange = 0f..1f,
                            enabled = false,
                            colors = seekBarColors,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (state.durationMs > 0) {
                                stringResource(
                                    R.string.player_time_progress,
                                    formatTime(state.positionMs),
                                    formatTime(state.durationMs),
                                )
                            } else {
                                stringResource(
                                    R.string.player_time_progress_unknown,
                                    formatTime(state.positionMs),
                                )
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 12.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                )
                            }
                            IconButton(onClick = onTogglePlayPause) {
                                Icon(
                                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = stringResource(
                                        if (state.isPlaying) R.string.player_pause else R.string.player_play,
                                    ),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            TextButton(onClick = onCycleSpeed) {
                                Text(
                                    stringResource(R.string.player_playback_speed, state.speed),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
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
        text = stringResource(R.string.player_gesture_seek, deltaText, formatTime(preview.targetPositionMs)),
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
    val text = stringResource(
        if (preview.isRewind) R.string.player_gesture_rewinding else R.string.player_gesture_speed,
        preview.speed,
    )
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** 竖向亮度/音量指示器（U3-C）：窄长圆角胶囊，半透明黑底。 */
@Composable
private fun GestureLevelIndicator(preview: GestureLevelPreview, modifier: Modifier = Modifier) {
    val icon = if (preview.kind == PlayerLevelKind.BRIGHTNESS) Icons.Default.LightMode else Icons.Default.VolumeUp
    Column(
        modifier = modifier
            .padding(horizontal = 32.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = stringResource(
                if (preview.kind == PlayerLevelKind.BRIGHTNESS) R.string.player_brightness else R.string.player_volume,
            ),
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(140.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(preview.fraction.coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White),
            )
        }
        Text(
            stringResource(R.string.player_level_percent, (preview.fraction * 100).roundToInt()),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 读取系统亮度（Android 12+ 优先 display info，fallback Settings.System），返回 0..1。 */
private fun readSystemBrightness(context: Context): Float {
    return try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (_: Exception) {
        0.5f
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0) return stringResource(R.string.player_download_speed_unknown)
    val kb = bytesPerSecond / 1024.0
    return if (kb < 1024) {
        stringResource(R.string.player_download_speed_kb, kb)
    } else {
        stringResource(R.string.player_download_speed_mb, kb / 1024.0)
    }
}

private fun readBatteryLevel(context: Context): Int? {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return level.takeIf { it in 0..100 }
}

private data class PlayerAudioSpectrumBridge(
    val provider: SpectrumProvider,
    val available: Boolean,
)

/**
 * Keeps high-rate FFT updates out of PlayerRoute's Compose state. The renderer samples the stable
 * provider from its draw pass; composition only observes the low-frequency null/non-null boundary.
 */
@Composable
private fun rememberAudioSpectrumBridge(
    audioBands: StateFlow<AudioBandLevels?>,
): PlayerAudioSpectrumBridge {
    val latestFrame = remember(audioBands) {
        AtomicReference(audioBands.value?.toSpectrumFrame())
    }
    var available by remember(audioBands) { mutableStateOf(audioBands.value != null) }
    LaunchedEffect(audioBands) {
        audioBands.collect { levels ->
            latestFrame.set(levels?.toSpectrumFrame())
            val nextAvailable = levels != null
            if (available != nextAvailable) available = nextAvailable
        }
    }
    val provider = remember(audioBands) {
        SpectrumProvider.alreadySmoothed { latestFrame.get() ?: SpectrumFrame.Zero }
    }
    return PlayerAudioSpectrumBridge(provider = provider, available = available)
}

private fun Context.isRecordAudioPermanentlyDenied(activity: Activity?): Boolean {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val wasRequested = getSharedPreferences(PERMISSION_HISTORY_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(RECORD_AUDIO_REQUESTED_KEY, false)
    return wasRequested && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.RECORD_AUDIO,
        )
}

private fun Context.markRecordAudioPermissionRequested() {
    getSharedPreferences(PERMISSION_HISTORY_PREFERENCES, Context.MODE_PRIVATE)
        .edit { putBoolean(RECORD_AUDIO_REQUESTED_KEY, true) }
}

private fun Context.openApplicationPermissionSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private const val OVERLAY_AUTO_HIDE_MS = 3_000L
private const val AUDIO_SPECTRUM_START_TIMEOUT_MS = 1_500L
private const val PERMISSION_HISTORY_PREFERENCES = "player_visual_permission_history"
private const val RECORD_AUDIO_REQUESTED_KEY = "record_audio_requested"
