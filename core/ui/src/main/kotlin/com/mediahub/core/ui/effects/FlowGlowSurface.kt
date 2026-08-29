package com.mediahub.core.ui.effects

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
class FlowGlowClock internal constructor() {

    var fps: Int = 30
        set(value) {
            field = value.coerceIn(1, 120)
        }

    var timeSec: Float by mutableFloatStateOf(0f)
        private set

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastNanos = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (lastNanos != 0L && now - lastNanos >= 1_000_000_000L / fps) {
                        timeSec += (now - lastNanos) / 1_000_000_000f
                        lastNanos = now
                    } else if (lastNanos == 0L) {
                        lastNanos = now
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/** Creates and lifecycle-binds a [FlowGlowClock]: started on composition, stopped on dispose. */
@Composable
fun rememberFlowGlowClock(fps: Int = 30): FlowGlowClock {
    val clock = remember { FlowGlowClock() }
    val scope = rememberCoroutineScope()
    SideEffect { clock.fps = fps }
    DisposableEffect(clock, scope) {
        clock.start(scope)
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
    clock: FlowGlowClock = rememberFlowGlowClock(fps = config.fps),
    shape: Shape = RoundedCornerShape(percent = 50),
    content: @Composable BoxScope.() -> Unit = {},
) {
    clock.fps = config.fps

    val shader = remember {
        if (Build.VERSION.SDK_INT >= 33) RuntimeShader(FlowGlowShader.SOURCE) else null
    }
    val paint = remember { Paint() }
    val smoother = remember { SmoothedSpectrum() }
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
            .background(Color(palette.background))
            .drawBehind {
                if (Build.VERSION.SDK_INT < 33) {
                    drawRect(fallbackBrush)
                    return@drawBehind
                }
                val agsl = shader ?: return@drawBehind
                val cfg = currentConfig
                val gain = cfg.audioGain.coerceIn(0f, 4f)
                val raw = currentSpectrum.sample(clock.timeSec.toDouble())
                val frame = SpectrumFrame(
                    bass = (raw.bass * gain).coerceIn(0f, 1f),
                    mid = (raw.mid * gain).coerceIn(0f, 1f),
                    treble = (raw.treble * gain).coerceIn(0f, 1f),
                )
                val smoothed = smoother.process(frame, dtSec = 1f / clock.fps.coerceAtLeast(1))
                FlowGlowShader.apply(
                    shader = agsl,
                    width = size.width,
                    height = size.height,
                    timeSec = clock.timeSec,
                    progress = currentProgress(),
                    palette = currentPalette,
                    config = cfg,
                    spectrum = smoothed,
                )
                paint.shader = agsl
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
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
