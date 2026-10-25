package com.mediahub.player.engine

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEnginePlaybackControlTest {
    @Test fun pauseSnapshotDoesNotOverwriteAReentrantReplacementSession() = runTest {
        val native = ControlledPlayer()
        val original = session()
        val engine = PlaybackEngine(native.proxy(), PlaybackHeadersHolder(), NoLogger, backgroundScope, PlaybackSpeedMonitor())
        val events = mutableListOf<PlaybackEventMessage>()
        var armed = false
        var replaced = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.collect { events += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.uiState.collect { state ->
                if (armed && !state.playWhenReady && !replaced) {
                    replaced = true
                    engine.play(original.copy(itemId = "new"))
                }
            }
        }
        try {
            engine.play(original); runCurrent()
            native.advanceBy(20_000)
            armed = true
            engine.pause()
            assertTrue(replaced)
            val paused = events.single { it.kind == PlaybackEvent.Paused }
            assertEquals(original.itemId, paused.progress!!.itemId)
            assertEquals(20_000L, paused.progress!!.positionMs)
            assertTrue(paused.progress!!.isPaused)
            assertTrue("Old pause must not overwrite the replacement's playing intent", engine.uiState.value.playWhenReady)
        } finally { engine.release() }
    }

    @Test fun endedSnapshotSurvivesSynchronousStateObserverReentry() = runTest {
        val native = ControlledPlayer()
        val original = session()
        val engine = PlaybackEngine(native.proxy(), PlaybackHeadersHolder(), NoLogger, backgroundScope, PlaybackSpeedMonitor())
        val events = mutableListOf<PlaybackEventMessage>()
        var replaced = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.collect { events += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.uiState.collect { state ->
                if (state.isEnded && !replaced) {
                    replaced = true
                    engine.play(session().copy(itemId = "new"))
                }
            }
        }
        try {
            engine.play(original); runCurrent()
            native.advanceBy(20_000)
            native.finish()
            assertTrue(replaced)
            val ended = events.single { it.kind == PlaybackEvent.Ended }
            assertEquals(original.itemId, ended.progress!!.itemId)
            assertEquals(20_000L, ended.progress!!.positionMs)
        } finally { engine.release() }
    }

    @Test
    fun `queued critical controls persist their own positions before the next periodic tick`() = runTest {
        val native = ControlledPlayer()
        val engine = PlaybackEngine(native.proxy(), PlaybackHeadersHolder(), NoLogger, backgroundScope, PlaybackSpeedMonitor())
        val local = mutableListOf<Long>()
        val remote = mutableListOf<Long>()
        val coordinator = ProgressSyncCoordinator(backgroundScope,
            localSave = { local += it.positionMs }, remoteReport = { remote += it.positionMs })
        try {
            coordinator.start(engine.progress, engine.events, remoteIntervalMs = 60_000)
            runCurrent()
            engine.play(session())
            runCurrent() // Initial zero position is sampled, then no periodic tick runs.
            engine.seekTo(20_000, SeekMode.COMMIT)
            engine.pause()
            engine.seekTo(40_000, SeekMode.COMMIT)
            runCurrent() // Consume the queued controls only after all positions changed.
            assertEquals(listOf(20_000L, 20_000L, 40_000L), local)
            assertEquals(local, remote)
        } finally { coordinator.stop(); engine.release() }
    }

    @Test
    fun `absolute controls use native intent even when UI observer lags`() = runTest {
        val native = ControlledPlayer()
        val engine = PlaybackEngine(native.proxy(), PlaybackHeadersHolder(), NoLogger, backgroundScope, PlaybackSpeedMonitor())
        try {
            engine.play(session())
            native.playWhenReady = false // no observer delivery; UI still offers Pause
            engine.setPlayWhenReady(false)
            assertFalse(native.playWhenReady)
            assertFalse(engine.uiState.value.playWhenReady)
            native.playWhenReady = true // UI still offers Play
            engine.setPlayWhenReady(true)
            assertTrue(native.playWhenReady)
            assertTrue(engine.uiState.value.playWhenReady)
            engine.setPlayWhenReady(false)
            engine.setPlayWhenReady(false)
            assertFalse(native.playWhenReady)
            engine.setPlayWhenReady(true)
            engine.setPlayWhenReady(true)
            assertTrue(native.playWhenReady)
        } finally { engine.release() }
    }

    @Test
    fun `toggle cancels play intent while buffering and resumes on the next click`() = runTest {
        val native = ControlledPlayer()
        val engine = PlaybackEngine(native.proxy(), PlaybackHeadersHolder(), NoLogger, backgroundScope, PlaybackSpeedMonitor())
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.collect { events += it.kind } }
        try {
            engine.play(session())
            native.playbackState = Player.STATE_BUFFERING
            assertTrue(native.playWhenReady)
            assertFalse(native.isPlaying)
            assertTrue("buffering must still offer a pause control", engine.uiState.value.playWhenReady)

            engine.togglePlayPause()
            assertFalse("buffering must not turn a pause request into play", native.playWhenReady)
            assertFalse(engine.uiState.value.playWhenReady)
            native.playbackState = Player.STATE_READY
            assertFalse("completion of buffering must preserve the user's pause", native.isPlaying)
            engine.togglePlayPause()
            assertTrue(native.isPlaying)
            assertTrue(engine.uiState.value.playWhenReady)
            assertEquals(listOf(PlaybackEvent.Paused, PlaybackEvent.Resumed), events)
        } finally { engine.release() }
    }

    @Test
    fun `ended playback retains the resume action instead of emitting pause`() = runTest {
        val native = ControlledPlayer()
        val engine = PlaybackEngine(native.proxy(), PlaybackHeadersHolder(), NoLogger, backgroundScope, PlaybackSpeedMonitor())
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.collect { events += it.kind } }
        try {
            engine.play(session())
            native.playbackState = Player.STATE_ENDED
            engine.togglePlayPause()
            assertTrue(native.playWhenReady)
            assertEquals(listOf(PlaybackEvent.Resumed), events)
        } finally { engine.release() }
    }

    @Test
    fun `toggle cancels play intent while output is suppressed`() = runTest {
        val native = ControlledPlayer()
        val engine = PlaybackEngine(native.proxy(), PlaybackHeadersHolder(), NoLogger, backgroundScope, PlaybackSpeedMonitor())
        try {
            engine.play(session())
            native.suppressed = true
            assertFalse(native.isPlaying)
            engine.togglePlayPause()
            assertFalse(native.playWhenReady)
            native.suppressed = false
            assertFalse("removing suppression must not undo pause", native.isPlaying)
        } finally { engine.release() }
    }

    @Test
    fun `pause freezes sampled progress and committed seek stays paused`() = runTest {
        val native = ControlledPlayer()
        val engine = PlaybackEngine(native.proxy(), PlaybackHeadersHolder(), NoLogger, backgroundScope, PlaybackSpeedMonitor())
        val progress = mutableListOf<PlaybackProgress>()
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.progress.collect { progress += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.collect { events += it.kind } }
        try {
            engine.play(session())
            runCurrent()
            native.advanceBy(15_000)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(15_000L, engine.uiState.value.positionMs)
            engine.togglePlayPause()
            repeat(3) {
                native.advanceBy(5_000)
                advanceTimeBy(1_000); runCurrent()
                assertEquals(15_000L, engine.uiState.value.positionMs)
                assertTrue(progress.last().isPaused)
            }
            engine.seekTo(45_000L, SeekMode.COMMIT)
            native.advanceBy(5_000)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(45_000L, engine.uiState.value.positionMs)
            assertTrue(progress.last().isPaused)
            engine.togglePlayPause()
            native.advanceBy(5_000)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(50_000L, progress.last().positionMs)
            assertFalse(progress.last().isPaused)
            assertEquals(listOf(PlaybackEvent.Paused, PlaybackEvent.Seeked, PlaybackEvent.Resumed), events)
        } finally { engine.release() }
    }

    private fun session() = PlaybackSession(
        serverId = "controlled-source", itemId = "fresh-control-test", itemTitle = "Controlled clip",
        source = PlaybackSource(url = "https://example.invalid/controlled.mp4", durationMs = 90_000L),
    )

    private class ControlledPlayer : InvocationHandler {
        private val trackSelector = DefaultTrackSelector(RuntimeEnvironment.getApplication())
        private val listeners = mutableListOf<Player.Listener>()
        var playWhenReady = false
        var playbackState = Player.STATE_READY
        var suppressed = false
        private var positionMs = 0L
        val isPlaying get() = playWhenReady && playbackState == Player.STATE_READY && !suppressed
        fun advanceBy(durationMs: Long) { if (isPlaying) positionMs += durationMs }
        fun finish() {
            playbackState = Player.STATE_ENDED
            listeners.forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
        }
        private fun requestPlayback(value: Boolean) {
            playWhenReady = value
            listeners.forEach {
                it.onPlayWhenReadyChanged(value, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                it.onIsPlayingChanged(isPlaying)
            }
        }
        fun proxy() = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java), this) as ExoPlayer
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getTrackSelector" -> trackSelector
            "addListener" -> { listeners += args!![0] as Player.Listener; null }
            "getPlayWhenReady" -> playWhenReady
            "setPlayWhenReady" -> { requestPlayback(args!![0] as Boolean); null }
            "isPlaying" -> isPlaying
            "pause" -> { requestPlayback(false); null }
            "play" -> { requestPlayback(true); null }
            "seekTo" -> { positionMs = args!![0] as Long; null }
            "getCurrentPosition" -> positionMs
            "getDuration" -> 90_000L
            "getPlaybackState" -> playbackState
            "getPlaybackSuppressionReason" -> if (suppressed) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else Player.PLAYBACK_SUPPRESSION_REASON_NONE
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 1f
                else -> null
            }
        }
    }

    private object NoLogger : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
}
