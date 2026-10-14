package com.mediahub.core.ui.effects

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.PlayerVisualPreset

/** Low-frequency product state for UI tests; never expose shader time as Compose semantics. */
object PlayerVisualSemantics {
    val Preset = SemanticsPropertyKey<String>("PlayerVisualPreset")
    val TargetFps = SemanticsPropertyKey<Int>("PlayerVisualTargetFps")
}

/** Complete renderer request shared by the production overlay and settings preview. */
data class PlayerVisualRenderRequest(
    val preset: PlayerVisualPreset,
    val palette: VisualPalette? = null,
    val intensity: Float = PlayerVisualEffectsPreferences.DEFAULT_INTENSITY,
    val audioReactive: Boolean = true,
    val frameDecision: VisualFrameDecision = VisualFrameDecision(
        running = true,
        targetFps = 30,
        motionScale = 1f,
    ),
)

/**
 * The one production renderer entry point. It deliberately knows nothing about Player, DataStore,
 * providers, or lifecycle; callers reduce those inputs to [PlayerVisualRenderRequest].
 */
@Composable
fun PlayerVisualRenderer(
    request: PlayerVisualRenderRequest,
    modifier: Modifier = Modifier,
    spectrum: SpectrumProvider = SpectrumProvider.Noop,
    progressProvider: () -> Float = { 0f },
    /** Optional clock shared by sibling edge/chrome renderers. */
    clock: FlowGlowClock? = null,
    forceFallback: Boolean = false,
    shape: Shape = RectangleShape,
    containerColor: Color = Color.Transparent,
    onBackendChanged: (RendererBackend) -> Unit = {},
) {
    val style = PlayerVisualPresetMapper.resolve(
        preset = request.preset,
        intensity = request.intensity,
        targetFps = request.frameDecision.targetFps.coerceAtLeast(1),
        paletteOverride = request.palette,
        motionScale = request.frameDecision.motionScale,
    )
    val shouldRender = request.frameDecision.running
    if (!shouldRender) {
        LaunchedEffect(Unit) { onBackendChanged(RendererBackend.NONE) }
        Box(modifier = modifier)
        return
    }

    FlowGlowSurface(
        palette = style.palette,
        modifier = modifier,
        config = style.config,
        spectrum = if (style.audioReactive && request.audioReactive) spectrum else SpectrumProvider.Noop,
        progressProvider = progressProvider,
        running = true,
        clock = clock,
        forceFallback = forceFallback,
        shape = shape,
        containerColor = containerColor,
        onBackendChanged = onBackendChanged,
    )
}

/** Settings preview that intentionally delegates to [PlayerVisualRenderer]. */
@Composable
fun PlayerVisualEffectPreview(
    request: PlayerVisualRenderRequest,
    modifier: Modifier = Modifier,
    spectrum: SpectrumProvider = SpectrumProvider.Noop,
    progressProvider: () -> Float = { 0f },
    shape: Shape = RoundedCornerShape(20.dp),
    onBackendChanged: (RendererBackend) -> Unit = {},
) {
    val sourcePalette = request.palette ?: PlayerVisualPresetMapper.resolve(
        preset = request.preset,
        intensity = request.intensity,
        targetFps = request.frameDecision.targetFps.coerceAtLeast(1),
        motionScale = request.frameDecision.motionScale,
    ).palette
    val chrome = PlayerVisualPalette.from(sourcePalette)
    Box(
        modifier = modifier
            .clip(shape)
            .background(chrome.surface),
    ) {
        PlayerVisualRenderer(
            request = request,
            // The caller's explicit preview height owns measurement; the renderer only fills it.
            modifier = Modifier.matchParentSize(),
            spectrum = spectrum,
            progressProvider = progressProvider,
            shape = shape,
            containerColor = Color.Transparent,
            onBackendChanged = onBackendChanged,
        )
    }
}

/**
 * Production-safe player overlay. It renders three small children rather than compositing the
 * whole SurfaceView tree: two edge strips and controls-only bottom chrome. The center video and
 * subtitle-safe band therefore contain no shader pixels by construction.
 */
@Composable
fun PlayerVisualOverlay(
    request: PlayerVisualRenderRequest,
    modifier: Modifier = Modifier,
    spectrum: SpectrumProvider = SpectrumProvider.Noop,
    progressProvider: () -> Float = { 0f },
    /** Pass the same clock to this edge overlay and [PlayerVisualChromeBackground]. */
    clock: FlowGlowClock? = null,
    /** Latched by the caller when any sibling reports [RendererBackend.FALLBACK_GRADIENT]. */
    forceFallback: Boolean = false,
    mask: PlayerVisualMaskConfig = PlayerVisualMaskConfig(),
    includeBottomChrome: Boolean = true,
    onBackendChanged: (RendererBackend) -> Unit = {},
) {
    val currentBackendCallback by rememberUpdatedState(onBackendChanged)
    val semanticModifier = modifier
        .testTag(PlayerVisualTestTags.OVERLAY)
        .semantics {
            this[PlayerVisualSemantics.Preset] = request.preset.name
            this[PlayerVisualSemantics.TargetFps] = request.frameDecision.targetFps
        }
    if (!request.frameDecision.running) {
        LaunchedEffect(Unit) { currentBackendCallback(RendererBackend.NONE) }
        Box(modifier = semanticModifier)
        return
    }

    var runtimeHealthy by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= RendererBackendSelector.RUNTIME_SHADER_MIN_API,
        )
    }
    val ownedClock = rememberFlowGlowClock(
        fps = request.frameDecision.targetFps.coerceAtLeast(1),
        running = clock == null &&
            request.frameDecision.running &&
            runtimeHealthy &&
            !forceFallback,
    )
    val activeClock = clock ?: ownedClock
    val effectiveForceFallback = forceFallback || !runtimeHealthy
    val reportBackend: (RendererBackend) -> Unit = { backend ->
        // A caller-forced fallback is retryable when its session latch is reset. A child that
        // fails without that external force is unhealthy for this composition and forces peers.
        if (backend == RendererBackend.FALLBACK_GRADIENT && !forceFallback) {
            runtimeHealthy = false
        }
        currentBackendCallback(
            if (forceFallback || !runtimeHealthy) {
                RendererBackend.FALLBACK_GRADIENT
            } else {
                backend
            },
        )
    }

    BoxWithConstraints(modifier = semanticModifier) {
        val edgeWidth = maxWidth * mask.edgeWidth
        val bottomHeight = maxHeight * (1f - mask.bottomStart)

        PlayerVisualRenderer(
            request = request,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(edgeWidth)
                .fillMaxHeight()
                .visualFade(FadeDirection.LEFT_EDGE),
            spectrum = spectrum,
            progressProvider = progressProvider,
            clock = activeClock,
            forceFallback = effectiveForceFallback,
            onBackendChanged = reportBackend,
        )
        PlayerVisualRenderer(
            request = request,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(edgeWidth)
                .fillMaxHeight()
                .visualFade(FadeDirection.RIGHT_EDGE),
            spectrum = spectrum,
            progressProvider = progressProvider,
            clock = activeClock,
            forceFallback = effectiveForceFallback,
            onBackendChanged = reportBackend,
        )
        if (includeBottomChrome) {
            PlayerVisualRenderer(
                request = request,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomHeight)
                    .visualFade(FadeDirection.BOTTOM_CHROME),
                spectrum = spectrum,
                progressProvider = progressProvider,
                clock = activeClock,
                forceFallback = effectiveForceFallback,
                onBackendChanged = reportBackend,
            )
        }
    }
}

/**
 * Background for the real bottom-controls container. The renderer is measured inside the
 * caller's container bounds, so no full-player or SurfaceView-root offscreen layer is created.
 * [maxAmbientHeight] lets playback restrict ambient to the root's bottom safe band while keeping
 * the full controls surface/scrim unchanged. It applies equally to AGSL and gradient fallback.
 * A dark vertical scrim is applied above the field by default for control and seek-label
 * readability; pass zero alphas to omit it.
 */
@Composable
fun PlayerVisualChromeBackground(
    request: PlayerVisualRenderRequest,
    modifier: Modifier = Modifier,
    spectrum: SpectrumProvider = SpectrumProvider.Noop,
    progressProvider: () -> Float = { 0f },
    /** Optional clock shared with [PlayerVisualOverlay]. */
    clock: FlowGlowClock? = null,
    /** Latched fallback state shared by every visual child in the player. */
    forceFallback: Boolean = false,
    shape: Shape = RectangleShape,
    maxAmbientHeight: Dp? = null,
    scrimColor: Color = Color.Black,
    scrimTopAlpha: Float = 0.18f,
    scrimBottomAlpha: Float = 0.68f,
    onBackendChanged: (RendererBackend) -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {},
) {
    val safeTopAlpha = scrimTopAlpha.finiteAlphaOr(default = 0.18f)
    val safeBottomAlpha = scrimBottomAlpha.finiteAlphaOr(default = 0.68f)
    val scrim = remember(scrimColor, safeTopAlpha, safeBottomAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                scrimColor.copy(alpha = scrimColor.alpha * safeTopAlpha),
                scrimColor.copy(alpha = scrimColor.alpha * safeBottomAlpha),
            ),
        )
    }

    Box(modifier = modifier.clip(shape)) {
        // This wrapper does not take part in measuring the controls. Its children receive loose
        // constraints, so a root-derived height cap cannot be defeated by matchParentSize's min.
        Box(modifier = Modifier.matchParentSize()) {
            val ambientBounds = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    if (maxAmbientHeight != null) {
                        Modifier.heightIn(max = maxAmbientHeight.coerceAtLeast(0.dp))
                    } else {
                        Modifier
                    },
                )
                .fillMaxHeight()
                .testTag(PlayerVisualTestTags.CHROME_AMBIENT)
            PlayerVisualRenderer(
                request = request,
                modifier = ambientBounds.visualFade(FadeDirection.BOTTOM_CHROME),
                spectrum = spectrum,
                progressProvider = progressProvider,
                clock = clock,
                forceFallback = forceFallback,
                shape = RectangleShape,
                containerColor = Color.Transparent,
                onBackendChanged = onBackendChanged,
            )
        }
        if (safeTopAlpha > 0f || safeBottomAlpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(scrim),
            )
        }
        content()
    }
}

private fun Float.finiteAlphaOr(default: Float): Float =
    takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: default

private enum class FadeDirection {
    LEFT_EDGE,
    RIGHT_EDGE,
    BOTTOM_CHROME,
}

/** Offscreen composition is deliberately scoped to each narrow overlay child, never PlayerRoot. */
private fun Modifier.visualFade(direction: FadeDirection): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val maskBrush = when (direction) {
                FadeDirection.LEFT_EDGE -> Brush.horizontalGradient(
                    colors = listOf(Color.White, Color.Transparent),
                )
                FadeDirection.RIGHT_EDGE -> Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.White),
                )
                FadeDirection.BOTTOM_CHROME -> Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.White),
                )
            }
            onDrawWithContent {
                drawContent()
                drawRect(brush = maskBrush, blendMode = BlendMode.DstIn)
            }
        }
