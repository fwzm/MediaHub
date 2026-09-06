package com.mediahub.core.ui.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.mediahub.model.VisualPerformanceMode
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Runs the actual composition-owned clock, not a mock timer or a static FPS comparison. */
class FlowGlowCompositionClockTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disabledHiddenStoppedAndDisposedCompositionsOwnNoFrameLoop() {
        val enabled = mutableStateOf(true)
        val controlsVisible = mutableStateOf(true)
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        val mounted = mutableStateOf(true)
        val captured = AtomicReference<FlowGlowClock>()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { lifecycleOwner.lifecycle.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                if (mounted.value) {
                    // Observe actual Lifecycle events through the same Compose API as PlayerRoute.
                    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
                    val policy = VisualFramePolicy.resolve(
                        VisualFrameInputs(
                            enabled = enabled.value,
                            lifecycleStarted = lifecycleState.isAtLeast(Lifecycle.State.STARTED),
                            controlsVisible = controlsVisible.value,
                            performanceMode = VisualPerformanceMode.BALANCED,
                        ),
                    )
                    val clock = rememberFlowGlowClock(
                        fps = policy.targetFps.coerceAtLeast(1),
                        running = policy.running,
                    )
                    SideEffect { captured.set(clock) }
                    Box {}
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        val clock = captured.get()
        assertTrue(clock.isRunning)
        assertTrue("the real Compose frame clock must advance", clock.timeSec > 0f)

        fun assertStopped(mutate: () -> Unit) {
            composeRule.runOnUiThread(mutate)
            composeRule.mainClock.advanceTimeBy(32)
            composeRule.waitForIdle()
            assertFalse(clock.isRunning)
            val stoppedAt = clock.timeSec
            composeRule.mainClock.advanceTimeBy(1_000)
            assertEquals("a stopped composition must not accumulate shader time", stoppedAt, clock.timeSec, 0f)
        }

        fun restart(mutate: () -> Unit) {
            val previous = clock.timeSec
            composeRule.runOnUiThread(mutate)
            composeRule.mainClock.advanceTimeBy(1_000)
            assertTrue(clock.isRunning)
            assertTrue(clock.timeSec > previous)
        }

        assertStopped { enabled.value = false }
        restart { enabled.value = true }
        assertStopped { controlsVisible.value = false }
        restart { controlsVisible.value = true }
        // Android represents a stopped-but-not-destroyed owner as CREATED.
        assertStopped { lifecycleOwner.lifecycle.currentState = Lifecycle.State.CREATED }
        restart { lifecycleOwner.lifecycle.currentState = Lifecycle.State.RESUMED }
        assertStopped { mounted.value = false }
        composeRule.runOnUiThread { lifecycleOwner.lifecycle.currentState = Lifecycle.State.DESTROYED }
    }
}
