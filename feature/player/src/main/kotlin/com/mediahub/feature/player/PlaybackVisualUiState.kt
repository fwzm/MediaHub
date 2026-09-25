package com.mediahub.feature.player

import com.mediahub.core.ui.effects.PlayerVisualPalette
import com.mediahub.core.ui.effects.PlayerVisualPresetMapper
import com.mediahub.core.ui.effects.PlayerVisualRenderRequest
import com.mediahub.core.ui.effects.RendererBackend
import com.mediahub.core.ui.effects.SpectrumFrame
import com.mediahub.core.ui.effects.VisualFrameDecision
import com.mediahub.core.ui.effects.VisualFrameInputs
import com.mediahub.core.ui.effects.VisualFramePolicy
import com.mediahub.core.ui.effects.VisualPalette
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.UserPreferences

/** Single immutable product state consumed by PlayerScreen's renderer and local visual theme. */
data class PlaybackVisualUiState(
    val preferences: PlayerVisualEffectsPreferences,
    val renderRequest: PlayerVisualRenderRequest,
    val sourcePalette: VisualPalette,
    val chromePalette: PlayerVisualPalette,
    val spectrum: SpectrumFrame,
    val audioReactiveAvailable: Boolean,
    val rendererBackend: RendererBackend = RendererBackend.NONE,
) {
    val enabled: Boolean get() = preferences.enabled
    val targetFps: Int get() = renderRequest.frameDecision.targetFps
}

/** Pure reduction of persistence, artwork, audio, lifecycle, and device policy into UI state. */
object PlaybackVisualStateResolver {
    /** DataStore has no synthetic visual default: before its first snapshot the renderer is Off. */
    fun preferencesForRenderer(stored: UserPreferences?): PlayerVisualEffectsPreferences =
        stored?.playerVisualEffects ?: PlayerVisualEffectsPreferences.Default.copy(enabled = false)

    fun resolve(
        preferences: PlayerVisualEffectsPreferences,
        artworkPalette: VisualPalette?,
        audioSpectrum: SpectrumFrame?,
        lifecycleStarted: Boolean,
        controlsVisible: Boolean,
        userInteracting: Boolean,
        powerSave: Boolean,
        reduceMotion: Boolean,
        rendererBackend: RendererBackend = RendererBackend.NONE,
    ): PlaybackVisualUiState {
        val normalized = preferences.normalized()
        val frameDecision = VisualFramePolicy.resolve(
            VisualFrameInputs(
                enabled = normalized.enabled,
                lifecycleStarted = lifecycleStarted,
                controlsVisible = controlsVisible,
                userInteracting = userInteracting,
                powerSave = powerSave,
                reduceMotion = reduceMotion,
                performanceMode = normalized.performanceMode,
                intensity = normalized.intensity,
            ),
        )
        val internalStyle = PlayerVisualPresetMapper.resolve(
            preset = normalized.preset,
            intensity = normalized.intensity,
            targetFps = frameDecision.targetFps.coerceAtLeast(1),
            motionScale = frameDecision.motionScale,
        )
        val sourcePalette = if (normalized.followArtworkColors) {
            artworkPalette ?: internalStyle.palette
        } else {
            internalStyle.palette
        }
        val spectrum = audioSpectrum?.sanitized() ?: SpectrumFrame.Zero
        return PlaybackVisualUiState(
            preferences = normalized,
            renderRequest = PlayerVisualRenderRequest(
                preset = normalized.preset,
                palette = sourcePalette,
                intensity = normalized.intensity,
                audioReactive = normalized.audioReactive && audioSpectrum != null,
                frameDecision = frameDecision,
            ),
            sourcePalette = sourcePalette,
            chromePalette = PlayerVisualPalette.from(sourcePalette),
            spectrum = spectrum,
            audioReactiveAvailable = audioSpectrum != null,
            rendererBackend = rendererBackend,
        )
    }

    private fun SpectrumFrame.sanitized(): SpectrumFrame = SpectrumFrame(
        bass = bass.finiteUnit(),
        mid = mid.finiteUnit(),
        treble = treble.finiteUnit(),
        amplitude = amplitude.finiteUnit(),
    )

    private fun Float.finiteUnit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
}
