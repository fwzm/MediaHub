package com.mediahub.core.ui.effects

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * One sampled snapshot of audio band energies. Every band is normalized to [0, 1].
 */
data class SpectrumFrame(
    val bass: Float,
    val mid: Float,
    val treble: Float,
) {
    val amplitude: Float get() = bass * 0.5f + mid * 0.35f + treble * 0.15f

    companion object {
        val Zero = SpectrumFrame(bass = 0f, mid = 0f, treble = 0f)
    }
}

/**
 * Source of realtime audio band energy consumed by the visual engine.
 *
 * Sampling happens once per rendered frame on the UI thread, so implementations must be
 * cheap and non-blocking. A Media3 `AudioProcessor`-backed implementation can replace the
 * demo/no-op sources later without touching any call site.
 */
fun interface SpectrumProvider {
    fun sample(timeSec: Double): SpectrumFrame

    companion object {
        /** Always-zero source: time-driven visuals only. */
        val Noop = SpectrumProvider { SpectrumFrame.Zero }
    }
}

/**
 * Asymmetric exponential moving average for a single band.
 *
 * Fast attack keeps transients punchy (drum hits make the glow swell); slow release avoids
 * flicker between frames. Both rates are exponential time constants in seconds.
 */
class EmaBandSmoother(
    private val attackTimeSec: Float = ATTACK_SEC,
    private val releaseTimeSec: Float = RELEASE_SEC,
) {
    private var value = 0f

    fun process(target: Float, dtSec: Float): Float {
        val clampedTarget = target.coerceIn(0f, 1f)
        val dt = dtSec.coerceIn(MIN_DT_SEC, MAX_DT_SEC)
        val tau = if (clampedTarget > value) attackTimeSec else releaseTimeSec
        value += (clampedTarget - value) * (1f - exp(-dt / tau))
        return value
    }

    fun reset() {
        value = 0f
    }

    companion object {
        const val ATTACK_SEC = 0.045f
        const val RELEASE_SEC = 0.25f
        const val MIN_DT_SEC = 1e-4f
        const val MAX_DT_SEC = 0.25f
    }
}

/** Applies [EmaBandSmoother] to all three bands with shared time constants. */
class SmoothedSpectrum(
    attackTimeSec: Float = EmaBandSmoother.ATTACK_SEC,
    releaseTimeSec: Float = EmaBandSmoother.RELEASE_SEC,
) {
    private val bass = EmaBandSmoother(attackTimeSec, releaseTimeSec)
    private val mid = EmaBandSmoother(attackTimeSec, releaseTimeSec)
    private val treble = EmaBandSmoother(attackTimeSec, releaseTimeSec)

    fun process(frame: SpectrumFrame, dtSec: Float): SpectrumFrame = SpectrumFrame(
        bass = bass.process(frame.bass, dtSec),
        mid = mid.process(frame.mid, dtSec),
        treble = treble.process(frame.treble, dtSec),
    )

    fun reset() {
        bass.reset()
        mid.reset()
        treble.reset()
    }
}

/**
 * Deterministic, music-like spectrum synthesized from a beat model (bass impulse per beat,
 * slow mid wobble, fast treble shimmer). Used by the demo screen and JVM tests; swap with a
 * real Media3 tap for production reactivity.
 */
class SimulatedSpectrumProvider(
    private val bpm: Double = DEFAULT_BPM,
) : SpectrumProvider {

    override fun sample(timeSec: Double): SpectrumFrame {
        val beatPeriod = 60.0 / bpm
        val sinceBeat = timeSec % beatPeriod
        val beatPulse = exp(-sinceBeat * BEAT_DECAY)
        val bar = sin(timeSec * TAU / (beatPeriod * BEATS_PER_BAR))

        val bass = (beatPulse * (0.72 + 0.28 * bar)).coerceIn(0.0, 1.0)
        val mid = (0.32 + 0.30 * sin(timeSec * 2.4) + 0.14 * sin(timeSec * 5.1 + 1.7))
            .coerceIn(0.0, 1.0)
        val treble = (0.22 + 0.20 * sin(timeSec * 7.3 + 0.9) + 0.10 * sin(timeSec * 11.7))
            .coerceIn(0.0, 1.0)

        return SpectrumFrame(bass.toFloat(), mid.toFloat(), treble.toFloat())
    }

    private companion object {
        const val DEFAULT_BPM = 96.0
        const val BEAT_DECAY = 9.0
        const val BEATS_PER_BAR = 4.0
        const val TAU = 2.0 * PI
    }
}
