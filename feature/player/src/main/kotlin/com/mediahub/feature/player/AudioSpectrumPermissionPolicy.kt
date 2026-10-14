package com.mediahub.feature.player

import com.mediahub.core.ui.effects.RendererBackend
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.PlayerVisualPreset
import com.mediahub.player.engine.EngineKind

/**
 * Pure permission/resource policy for production audio-reactive visuals.
 *
 * A persisted Spectrum preference is not itself permission to display a system dialog. The
 * request is armed only by an explicit user action in the current player, and an attempted
 * request stays monotonic for that route even when controls hide or the lifecycle stops.
 */
internal object AudioSpectrumPermissionPolicy {
    fun resolve(inputs: AudioSpectrumPermissionInputs): AudioSpectrumPermissionDecision {
        val visual = inputs.preferences?.normalized()
        val hasConsumer = visual != null &&
            visual.enabled &&
            visual.preset == PlayerVisualPreset.SPECTRUM &&
            visual.audioReactive &&
            visual.intensity > 0f &&
            inputs.resolveReady &&
            inputs.engineKind == EngineKind.MEDIA3 &&
            inputs.rendererBackend == RendererBackend.RUNTIME_SHADER &&
            inputs.lifecycleStarted &&
            inputs.consumerVisible

        return AudioSpectrumPermissionDecision(
            hasConsumer = hasConsumer,
            captureEnabled = hasConsumer && inputs.permissionGranted,
            requestPermission = hasConsumer &&
                !inputs.permissionGranted &&
                inputs.explicitRequestArmed &&
                !inputs.requestAttemptedThisRoute,
        )
    }
}

internal data class AudioSpectrumPermissionInputs(
    /** null means DataStore has not emitted yet; no renderer or permission work is allowed. */
    val preferences: PlayerVisualEffectsPreferences?,
    val resolveReady: Boolean,
    val engineKind: EngineKind,
    val rendererBackend: RendererBackend,
    val lifecycleStarted: Boolean,
    val consumerVisible: Boolean,
    val permissionGranted: Boolean,
    val explicitRequestArmed: Boolean,
    val requestAttemptedThisRoute: Boolean,
)

internal data class AudioSpectrumPermissionDecision(
    val hasConsumer: Boolean,
    val captureEnabled: Boolean,
    val requestPermission: Boolean,
)
