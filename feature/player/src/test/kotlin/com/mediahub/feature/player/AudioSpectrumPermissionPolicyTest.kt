package com.mediahub.feature.player

import com.mediahub.core.ui.effects.RendererBackend
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.PlayerVisualPreset
import com.mediahub.player.engine.EngineKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSpectrumPermissionPolicyTest {

    @Test
    fun `only ready Media3 runtime shader consumer may capture`() {
        val granted = validInputs(permissionGranted = true)

        assertTrue(AudioSpectrumPermissionPolicy.resolve(granted).captureEnabled)
        assertFalse(
            AudioSpectrumPermissionPolicy.resolve(granted.copy(resolveReady = false)).hasConsumer,
        )
        assertFalse(
            AudioSpectrumPermissionPolicy.resolve(granted.copy(engineKind = EngineKind.MPV)).hasConsumer,
        )
        assertFalse(
            AudioSpectrumPermissionPolicy.resolve(
                granted.copy(rendererBackend = RendererBackend.FALLBACK_GRADIENT),
            ).hasConsumer,
        )
        assertFalse(
            AudioSpectrumPermissionPolicy.resolve(granted.copy(lifecycleStarted = false)).hasConsumer,
        )
        assertFalse(
            AudioSpectrumPermissionPolicy.resolve(granted.copy(consumerVisible = false)).hasConsumer,
        )
    }

    @Test
    fun `zero intensity off and unloaded preferences never request or capture`() {
        val base = validInputs(permissionGranted = false, explicitRequestArmed = true)

        assertFalse(
            AudioSpectrumPermissionPolicy.resolve(base.copy(preferences = null)).requestPermission,
        )
        assertFalse(
            AudioSpectrumPermissionPolicy.resolve(
                base.copy(preferences = spectrumPreferences().copy(enabled = false)),
            ).requestPermission,
        )
        assertFalse(
            AudioSpectrumPermissionPolicy.resolve(
                base.copy(preferences = spectrumPreferences().copy(intensity = 0f)),
            ).requestPermission,
        )
    }

    @Test
    fun `persisted Spectrum alone never auto requests sensitive permission`() {
        val decision = AudioSpectrumPermissionPolicy.resolve(
            validInputs(permissionGranted = false, explicitRequestArmed = false),
        )

        assertTrue(decision.hasConsumer)
        assertFalse(decision.captureEnabled)
        assertFalse(decision.requestPermission)
    }

    @Test
    fun `explicit Spectrum action requests once and attempt remains monotonic across visibility`() {
        val first = validInputs(permissionGranted = false, explicitRequestArmed = true)
        assertTrue(AudioSpectrumPermissionPolicy.resolve(first).requestPermission)

        val attempted = first.copy(requestAttemptedThisRoute = true)
        assertFalse(AudioSpectrumPermissionPolicy.resolve(attempted).requestPermission)
        assertFalse(
            AudioSpectrumPermissionPolicy.resolve(attempted.copy(consumerVisible = false))
                .requestPermission,
        )
        assertFalse(
            AudioSpectrumPermissionPolicy.resolve(attempted.copy(lifecycleStarted = false))
                .requestPermission,
        )
        assertFalse(AudioSpectrumPermissionPolicy.resolve(attempted).requestPermission)
    }

    private fun validInputs(
        permissionGranted: Boolean,
        explicitRequestArmed: Boolean = false,
    ) = AudioSpectrumPermissionInputs(
        preferences = spectrumPreferences(),
        resolveReady = true,
        engineKind = EngineKind.MEDIA3,
        rendererBackend = RendererBackend.RUNTIME_SHADER,
        lifecycleStarted = true,
        consumerVisible = true,
        permissionGranted = permissionGranted,
        explicitRequestArmed = explicitRequestArmed,
        requestAttemptedThisRoute = false,
    )

    private fun spectrumPreferences() = PlayerVisualEffectsPreferences(
        enabled = true,
        preset = PlayerVisualPreset.SPECTRUM,
        intensity = 0.35f,
        audioReactive = true,
    )
}
