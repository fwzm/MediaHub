package com.mediahub.core.ui.effects

/**
 * Measures the real time between consecutive draw passes.
 *
 * Temporal smoothing has to use the interval the display actually delivered. Using the clock's
 * nominal `1f / fps` made the bars ease at the audio sampling rate instead of the draw rate, so
 * the smoothing strength changed with the configured fps rather than with the observed cadence
 * (PR #10).
 */
internal class DrawDeltaClock(
    private val nanos: () -> Long = System::nanoTime,
) {
    private var lastNanos = 0L

    /** Seconds since the previous call, or [fallbackSec] when there is no usable baseline. */
    fun nextSec(fallbackSec: Float): Float {
        val now = nanos()
        val previous = lastNanos
        lastNanos = now
        if (previous == 0L) return fallbackSec
        val deltaSec = (now - previous) / 1_000_000_000f
        if (deltaSec <= 0f) return fallbackSec
        return deltaSec.coerceAtMost(MAX_DELTA_SEC)
    }

    companion object {
        /** A stalled frame must not be trusted as a smoothing step. */
        const val MAX_DELTA_SEC = 0.1f
    }
}
