package com.mediahub.core.ui.effects

import com.mediahub.model.PlayerVisualPreset
import com.mediahub.model.VisualPerformanceMode
import kotlin.math.min

/** Actual renderer selected for the current device and shader health. */
enum class RendererBackend {
    NONE,
    RUNTIME_SHADER,
    FALLBACK_GRADIENT,
}

/** Pure backend selection so product state and tests do not need to load Android shader APIs. */
object RendererBackendSelector {
    const val RUNTIME_SHADER_MIN_API = 33

    fun select(
        apiLevel: Int,
        enabled: Boolean,
        runtimeShaderUsable: Boolean = true,
        forceFallback: Boolean = false,
    ): RendererBackend = when {
        !enabled -> RendererBackend.NONE
        forceFallback -> RendererBackend.FALLBACK_GRADIENT
        apiLevel >= RUNTIME_SHADER_MIN_API && runtimeShaderUsable -> RendererBackend.RUNTIME_SHADER
        else -> RendererBackend.FALLBACK_GRADIENT
    }
}

/**
 * Pure coordination rules for multiple renderer children sharing one clock. A fallback report is
 * latched for the current playback/runtime session; the owner resets the latch only when it is
 * safe to retry, such as on a new playback session.
 */
object PlayerVisualRuntimePolicy {
    fun latchForceFallback(
        currentForceFallback: Boolean,
        reportedBackend: RendererBackend,
    ): Boolean = currentForceFallback || reportedBackend == RendererBackend.FALLBACK_GRADIENT

    fun shouldRunSharedClock(
        requestedRunning: Boolean,
        forceFallback: Boolean,
    ): Boolean = requestedRunning && !forceFallback
}

/** Inputs that determine whether a visual loop exists and, if so, how fast it may run. */
data class VisualFrameInputs(
    val enabled: Boolean,
    val lifecycleStarted: Boolean,
    val controlsVisible: Boolean,
    val userInteracting: Boolean = false,
    val powerSave: Boolean = false,
    val reduceMotion: Boolean = false,
    val performanceMode: VisualPerformanceMode = VisualPerformanceMode.AUTO,
    val intensity: Float = 0.35f,
)

/** A zero target means there must be no frame-clock job, not a one-fps approximation. */
data class VisualFrameDecision(
    val running: Boolean,
    val targetFps: Int,
    val motionScale: Float,
) {
    init {
        require(targetFps in ALLOWED_FRAME_RATES) { "targetFps must be one of $ALLOWED_FRAME_RATES" }
        require(running == (targetFps > 0)) { "running must agree with targetFps" }
        require(motionScale in 0f..1f) { "motionScale must be in 0..1" }
    }

    companion object {
        private val ALLOWED_FRAME_RATES = setOf(0, 15, 30, 60)
        val Stopped = VisualFrameDecision(running = false, targetFps = 0, motionScale = 0f)
    }
}

/**
 * Product frame policy. Visibility and lifecycle are hard gates; performance modes only choose
 * among 15/30/60 after those gates pass.
 */
object VisualFramePolicy {
    fun resolve(inputs: VisualFrameInputs): VisualFrameDecision {
        val intensity = inputs.intensity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        if (!inputs.enabled ||
            !inputs.lifecycleStarted ||
            !inputs.controlsVisible ||
            intensity <= MIN_RENDERABLE_INTENSITY
        ) {
            return VisualFrameDecision.Stopped
        }

        var fps = when (inputs.performanceMode) {
            VisualPerformanceMode.BATTERY -> 15
            VisualPerformanceMode.BALANCED -> 30
            VisualPerformanceMode.HIGH -> 60
            VisualPerformanceMode.AUTO -> when {
                inputs.userInteracting -> 60
                intensity < LOW_INTENSITY_THRESHOLD -> 15
                else -> 30
            }
        }
        if (inputs.powerSave || inputs.reduceMotion) fps = min(fps, 15)

        val motionScale = when {
            inputs.reduceMotion -> REDUCED_MOTION_SCALE
            inputs.powerSave -> POWER_SAVE_MOTION_SCALE
            else -> 1f
        }
        return VisualFrameDecision(running = true, targetFps = fps, motionScale = motionScale)
    }

    /** Pure settings-preview policy; previews obey the same hard stops as playback. */
    fun resolvePreview(
        enabled: Boolean,
        lifecycleStarted: Boolean,
        performanceMode: VisualPerformanceMode,
        intensity: Float,
        powerSave: Boolean = false,
        reduceMotion: Boolean = false,
    ): VisualFrameDecision = resolve(
        VisualFrameInputs(
            enabled = enabled,
            lifecycleStarted = lifecycleStarted,
            controlsVisible = true,
            performanceMode = performanceMode,
            intensity = intensity,
            powerSave = powerSave,
            reduceMotion = reduceMotion,
        ),
    )

    private const val MIN_RENDERABLE_INTENSITY = 1e-3f
    private const val LOW_INTENSITY_THRESHOLD = 0.25f
    private const val REDUCED_MOTION_SCALE = 0.2f
    private const val POWER_SAVE_MOTION_SCALE = 0.65f
}

/** A resolved, renderer-ready style. It contains no player/provider state. */
data class PlayerVisualRenderStyle(
    val preset: PlayerVisualPreset,
    val palette: VisualPalette,
    val config: VisualEffectConfig,
    val audioReactive: Boolean,
)

/** Stable mapping from user-facing choices to the existing FlowGlow renderer assets. */
object PlayerVisualPresetMapper {
    fun resolve(
        preset: PlayerVisualPreset,
        intensity: Float,
        targetFps: Int,
        paletteOverride: VisualPalette? = null,
        motionScale: Float = 1f,
    ): PlayerVisualRenderStyle {
        val safeIntensity = intensity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val safeMotion = motionScale.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val safeFps = targetFps.coerceIn(1, 120)
        val base = when (preset) {
            PlayerVisualPreset.AURORA -> FlowGlowPresets.AuroraDark
            PlayerVisualPreset.LIQUID -> FlowGlowPresets.NebulaCinema
            PlayerVisualPreset.SPECTRUM -> FlowGlowPresets.AuroraDark
        }
        val tuned = when (preset) {
            PlayerVisualPreset.AURORA -> base.config.copy(
                speed = 0.58f * safeMotion,
                warp = 0.72f,
                iridescence = 0.16f,
                bloom = 0.72f,
                opacity = 0.42f * safeIntensity,
                audioGain = 0.55f,
                fps = safeFps,
            )
            PlayerVisualPreset.LIQUID -> base.config.copy(
                speed = 0.82f * safeMotion,
                warp = 1.8f,
                iridescence = 0.38f,
                bloom = 0.86f,
                opacity = 0.50f * safeIntensity,
                noiseScale = 1.25f,
                audioGain = 0.8f,
                fps = safeFps,
            )
            PlayerVisualPreset.SPECTRUM -> base.config.copy(
                speed = 0.72f * safeMotion,
                warp = 1.15f,
                iridescence = 0.42f,
                bloom = 0.95f,
                opacity = 0.46f * safeIntensity,
                audioGain = 1.35f,
                fps = safeFps,
            )
        }
        return PlayerVisualRenderStyle(
            preset = preset,
            palette = paletteOverride ?: base.palette,
            config = tuned,
            audioReactive = preset == PlayerVisualPreset.SPECTRUM,
        )
    }
}

/** Normalized geometry for edge ambient and controls-only bottom chrome. */
data class PlayerVisualMaskConfig(
    val edgeWidth: Float = 0.08f,
    val bottomStart: Float = 0.91f,
    val subtitleLeft: Float = 0.12f,
    val subtitleRight: Float = 0.88f,
    val subtitleTop: Float = 0.55f,
    val subtitleBottom: Float = 0.91f,
) {
    init {
        require(edgeWidth in 0.001f..0.25f)
        require(bottomStart >= 0.5f && bottomStart < 1f)
        require(subtitleLeft >= 0f && subtitleLeft < subtitleRight && subtitleRight <= 1f)
        require(subtitleTop >= 0f && subtitleTop < subtitleBottom && subtitleBottom <= bottomStart)
        require(edgeWidth <= subtitleLeft && edgeWidth <= 1f - subtitleRight) {
            "edge strips must remain outside the subtitle-safe horizontal band"
        }
    }
}

/**
 * Pure mask used by the production overlay. The video center and conventional subtitle-safe
 * region return exactly zero, including for mpv subtitles rendered inside the SurfaceView.
 */
object PlayerVisualMask {
    fun alphaAt(
        x: Float,
        y: Float,
        controlsVisible: Boolean,
        config: PlayerVisualMaskConfig = PlayerVisualMaskConfig(),
    ): Float {
        if (!controlsVisible || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) {
            return 0f
        }
        if (x in config.subtitleLeft..config.subtitleRight &&
            y in config.subtitleTop..config.subtitleBottom
        ) {
            return 0f
        }

        val edgeDistance = min(x, 1f - x)
        val edge = if (edgeDistance < config.edgeWidth) {
            smoothStep(config.edgeWidth, 0f, edgeDistance)
        } else {
            0f
        }
        val bottom = if (y > config.bottomStart) {
            smoothStep(config.bottomStart, 1f, y)
        } else {
            0f
        }
        return maxOf(edge, bottom).coerceIn(0f, 1f)
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value < edge0) 0f else 1f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
