package com.mediahub.core.ui.effects

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

/**
 * Drives shader time at a target fps. The loop listens to composition frames and only
 * advances [timeSec] once per interval, so a 30 fps clock simply skips every other vsync
 * while the UI keeps running at display rate. Multiple surfaces may share one clock.
 */
class FlowGlowClock internal constructor(
    private val awaitFrameNanos: suspend () -> Long = { withFrameNanos { it } },
) {

    var fps: Int = 30
        set(value) {
            field = value.coerceIn(1, 120)
        }

    var timeSec: Float by mutableFloatStateOf(0f)
        private set

    private var job: Job? = null

    /** Observable for tests/diagnostics; a stopped clock owns no coroutine job. */
    val isRunning: Boolean
        get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastNanos = 0L
            while (isActive) {
                val now = awaitFrameNanos()
                if (lastNanos != 0L && now - lastNanos >= 1_000_000_000L / fps) {
                    timeSec += (now - lastNanos) / 1_000_000_000f
                    lastNanos = now
                } else if (lastNanos == 0L) {
                    lastNanos = now
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * Creates and lifecycle-binds a [FlowGlowClock]. [running] is a hard gate: false cancels and
 * clears the frame job. The default also avoids an empty clock on API levels with only a static
 * fallback renderer.
 */
@Composable
fun rememberFlowGlowClock(
    fps: Int = 30,
    running: Boolean = Build.VERSION.SDK_INT >= RendererBackendSelector.RUNTIME_SHADER_MIN_API,
): FlowGlowClock {
    val clock = remember { FlowGlowClock() }
    val scope = rememberCoroutineScope()
    SideEffect { clock.fps = fps }
    DisposableEffect(clock, scope, running) {
        if (running) clock.start(scope) else clock.stop()
        onDispose { clock.stop() }
    }
    return clock
}

/**
 * Rounded container filled with the GPU flow-glow field.
 *
 * The shader only consumes [VisualPalette] + [VisualEffectConfig] + [SpectrumProvider] state;
 * nothing here knows about players or providers. On devices below API 33 the field degrades
 * to a static palette gradient so callers need no version branching.
 *
 * [progressProvider] lets playback position slowly evolve the field (0..1 of the duration);
 * pass `{ 0f }` when no playback is attached.
 */
@Composable
fun FlowGlowSurface(
    palette: VisualPalette,
    modifier: Modifier = Modifier,
    config: VisualEffectConfig = VisualEffectConfig.Default,
    spectrum: SpectrumProvider = SpectrumProvider.Noop,
    progressProvider: () -> Float = { 0f },
    running: Boolean = true,
    /** Optional clock shared by sibling edge/chrome renderers. */
    clock: FlowGlowClock? = null,
    /** Forces the static gradient backend without constructing a player-root layer. */
    forceFallback: Boolean = false,
    shape: Shape = RoundedCornerShape(percent = 50),
    containerColor: Color = Color(palette.background),
    onBackendChanged: (RendererBackend) -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shaderCreation = remember {
        if (Build.VERSION.SDK_INT >= RendererBackendSelector.RUNTIME_SHADER_MIN_API) {
            runCatching { RuntimeShader(FlowGlowShader.SOURCE) }
        } else {
            Result.success(null)
        }
    }
    val shader = shaderCreation.getOrNull()
    var runtimeShaderUsable by remember(shader) { mutableStateOf(shader != null) }
    val ownedClock = rememberFlowGlowClock(
        fps = config.fps,
        running = clock == null && running && runtimeShaderUsable && !forceFallback,
    )
    val activeClock = clock ?: ownedClock
    SideEffect { activeClock.fps = config.fps }

    val currentBackendCallback by rememberUpdatedState(onBackendChanged)
    val backend = RendererBackendSelector.select(
        apiLevel = Build.VERSION.SDK_INT,
        enabled = true,
        runtimeShaderUsable = runtimeShaderUsable,
        forceFallback = forceFallback,
    )
    LaunchedEffect(backend, activeClock) {
        if (backend == RendererBackend.FALLBACK_GRADIENT) activeClock.stop()
        currentBackendCallback(backend)
    }
    LaunchedEffect(shaderCreation.exceptionOrNull()) {
        shaderCreation.exceptionOrNull()?.let { error ->
            Log.w(TAG, "RuntimeShader construction failed; using gradient fallback", error)
        }
    }

    val paint = remember { Paint() }
    val spectrumProcessor = remember(spectrum) { RendererSpectrumProcessor() }
    val fallbackBrush = remember(palette) {
        Brush.linearGradient(
            colors = listOf(Color(palette.secondary), Color(palette.primary), Color(palette.accent)),
        )
    }
    val currentPalette by rememberUpdatedState(palette)
    val currentConfig by rememberUpdatedState(config)
    val currentSpectrum by rememberUpdatedState(spectrum)
    val currentProgress by rememberUpdatedState(progressProvider)

    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .drawBehind {
                val cfg = currentConfig
                if (forceFallback ||
                    !runtimeShaderUsable ||
                    Build.VERSION.SDK_INT < RendererBackendSelector.RUNTIME_SHADER_MIN_API
                ) {
                    drawRect(fallbackBrush, alpha = cfg.opacity)
                    return@drawBehind
                }
                val agsl = shader ?: return@drawBehind
                try {
                    val preparedSpectrum = spectrumProcessor.process(
                        provider = currentSpectrum,
                        timeSec = activeClock.timeSec.toDouble(),
                        audioGain = cfg.audioGain,
                        dtSec = 1f / activeClock.fps.coerceAtLeast(1),
                    )
                    FlowGlowShader.apply(
                        shader = agsl,
                        width = size.width,
                        height = size.height,
                        timeSec = activeClock.timeSec,
                        progress = currentProgress(),
                        palette = currentPalette,
                        config = cfg,
                        spectrum = preparedSpectrum,
                    )
                    paint.shader = agsl
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
                    }
                } catch (error: RuntimeException) {
                    // Vendor shader compilers/drivers may reject otherwise valid AGSL. Fall back
                    // in the same frame and stop the owned animation clock on recomposition.
                    if (runtimeShaderUsable) {
                        runtimeShaderUsable = false
                        activeClock.stop()
                        Log.w(TAG, "RuntimeShader draw failed; using gradient fallback", error)
                        currentBackendCallback(RendererBackend.FALLBACK_GRADIENT)
                    }
                    paint.shader = null
                    drawRect(fallbackBrush, alpha = cfg.opacity)
                }
            },
    ) { content() }
}

/**
 * Tiny three-bar spectrum readout driven by the same [clock] as a [FlowGlowSurface]; used by
 * the demo screen and handy for debugging audio-reactive tuning.
 */
@Composable
fun SpectrumBars(
    clock: FlowGlowClock,
    provider: SpectrumProvider,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    smoother: SmoothedSpectrum = remember { SmoothedSpectrum() },
) {
    val currentProvider by rememberUpdatedState(provider)
    Box(
        modifier = modifier.drawBehind {
            val s = smoother.process(
                currentProvider.sample(clock.timeSec.toDouble()),
                dtSec = 1f / clock.fps.coerceAtLeast(1),
            )
            val slot = size.width / 3f
            val levels = listOf(s.bass, s.mid, s.treble)
            levels.forEachIndexed { index, level ->
                val barHeight = (0.15f + 0.85f * level) * size.height
                drawRoundRect(
                    color = color,
                    topLeft = Offset(index * slot + slot * 0.22f, size.height - barHeight),
                    size = Size(slot * 0.56f, barHeight),
                    cornerRadius = CornerRadius(slot * 0.28f),
                )
            }
        },
    )
}

private const val TAG = "FlowGlowSurface"
