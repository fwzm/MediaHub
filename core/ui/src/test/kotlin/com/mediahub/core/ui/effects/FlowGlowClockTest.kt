package com.mediahub.core.ui.effects

import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract PR #10 flagged: a 24 fps clock on a 60 Hz display must still advance 24
 * times a second. The old loop reset its baseline to the current frame time, so the 8.33 ms
 * left over from every 41.67 ms interval was dropped and the shader only advanced ~20 times.
 */
class FlowGlowClockTest {

    @Test
    fun `24 fps on a 60 hz display is advanced 24 times a second`() = runTest {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = FlowGlowClock(awaitFrameNanos = { frames.receive() })
        clock.fps = 24
        clock.start(backgroundScope)

        val stepNanos = 1_000_000_000L / 60
        var now = 0L
        val steps = mutableListOf<Float>()
        var previous = clock.timeSec
        repeat(61) {
            now += stepNanos
            frames.send(now)
            runCurrent()
            if (clock.timeSec != previous) {
                steps += clock.timeSec - previous
                previous = clock.timeSec
            }
        }
        clock.stop()

        assertTrue(
            "24 fps on 60 Hz used to advance 20 times a second, got ${steps.size}",
            steps.size in 23..25,
        )
    }

    @Test
    fun `every advance is a whole multiple of the frame interval`() = runTest {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = FlowGlowClock(awaitFrameNanos = { frames.receive() })
        clock.fps = 24
        clock.start(backgroundScope)

        val stepNanos = 1_000_000_000L / 60
        var now = 0L
        val steps = mutableListOf<Float>()
        var previous = clock.timeSec
        repeat(61) {
            now += stepNanos
            frames.send(now)
            runCurrent()
            if (clock.timeSec != previous) {
                steps += clock.timeSec - previous
                previous = clock.timeSec
            }
        }
        clock.stop()

        val interval = 1f / 24
        assertTrue("expected advances, got none", steps.isNotEmpty())
        steps.forEach { step ->
            val ticks = step / interval
            assertTrue(
                "step $step s is not a whole number of 1/24 s intervals ($ticks)",
                abs(ticks - round(ticks)) < 0.05f,
            )
        }
    }

}
