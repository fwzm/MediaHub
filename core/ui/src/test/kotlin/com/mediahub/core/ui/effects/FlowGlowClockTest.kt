package com.mediahub.core.ui.effects

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
