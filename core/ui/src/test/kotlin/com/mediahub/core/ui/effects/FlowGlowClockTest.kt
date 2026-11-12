package com.mediahub.core.ui.effects

import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract PR #10 flagged: a 24 fps clock on a 60 Hz display must still advance 24
 * times a second. The old loop reset its baseline to the current frame time, so the 8.33 ms
 * left over from every 41.67 ms interval was dropped and the shader only advanced ~20 times.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowGlowClockTest {

    @Test
    fun `stop cancels the real frame job and restart does not include the paused gap`() = runTest {
        val frames = Channel<Long>(capacity = Channel.UNLIMITED)
        val clock = FlowGlowClock(awaitFrameNanos = { frames.receive() }).apply { fps = 60 }

        clock.start(backgroundScope)
        runCurrent()
        assertTrue(clock.isRunning)
        frames.trySend(1_000_000_000L)
        runCurrent()
        frames.trySend(1_016_666_667L)
        runCurrent()
        val beforeStop = clock.timeSec
        assertTrue(beforeStop > 0f)

        clock.stop()
        runCurrent()
        assertFalse(clock.isRunning)
        assertEquals(beforeStop, clock.timeSec, 0f)

        clock.start(backgroundScope)
        runCurrent()
        frames.trySend(100_000_000_000L)
        runCurrent()
        assertEquals("first frame after restart is a new baseline", beforeStop, clock.timeSec, 0f)
        frames.trySend(100_016_666_667L)
        runCurrent()
        assertTrue(clock.timeSec > beforeStop)
    }

    @Test
    fun `start and stop are idempotent and fps remains bounded`() = runTest {
        val frames = Channel<Long>(capacity = Channel.UNLIMITED)
        val clock = FlowGlowClock(awaitFrameNanos = { frames.receive() })
        clock.fps = 0
        assertEquals(1, clock.fps)
        clock.fps = 1_000
        assertEquals(120, clock.fps)

        clock.start(backgroundScope)
        clock.start(backgroundScope)
        runCurrent()
        assertTrue(clock.isRunning)
        clock.stop()
        clock.stop()
        runCurrent()
        assertFalse(clock.isRunning)
    }

    @Test
    fun `24 fps on a 60 hz display is advanced 24 times a second`() = runTest {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = FlowGlowClock(awaitFrameNanos = { frames.receive() })
        clock.fps = 24
        clock.start(backgroundScope)

        val steps = collectAdvanceSteps(clock, frames)
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

        val steps = collectAdvanceSteps(clock, frames)
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

    /** Feeds exactly one second of 60 Hz frames and returns the time added by each advance. */
    private suspend fun TestScope.collectAdvanceSteps(
        clock: FlowGlowClock,
        frames: Channel<Long>,
    ): List<Float> {
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
        return steps
    }
}
