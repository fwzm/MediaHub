package com.mediahub.player.engine

import com.mediahub.core.network.PlaybackError
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SwitchablePlaybackEngine 双内核门面测试（U3-A）。
 *
 * 验证：初始选择、错误降级（保位置重播 + 指纹记录）、无声降级、
 * 显式 Media3 模式不降级、网络错误不降级、mpv 失败不再循环、控制委托与 Surface 重绑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchablePlaybackEngineTest {

    private val dispatcher = StandardTestDispatcher()

    private val noOpLogger = object : com.mediahub.core.logging.Logger {
        override fun d(tag: com.mediahub.core.logging.LogTag, message: String) = Unit
        override fun i(tag: com.mediahub.core.logging.LogTag, message: String) = Unit
        override fun w(tag: com.mediahub.core.logging.LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: com.mediahub.core.logging.LogTag, message: String, throwable: Throwable?) = Unit
    }

    private fun session(source: PlaybackSource) = PlaybackSession(
        serverId = "srv-1",
        itemId = "item-1",
        itemTitle = "电影",
        source = source,
    )

    private fun source(audio: String? = "aac", container: String? = "mkv") = PlaybackSource(
        url = "http://media/stream",
        container = container,
        videoCodec = "h264",
        audioCodec = audio,
    )

    private fun facade(
        scope: CoroutineScope,
        media3: FakeEngine,
        mpv: FakeEngine,
        history: InMemoryEnginePreferenceHistory = InMemoryEnginePreferenceHistory(),
        mode: PlaybackEngineMode = PlaybackEngineMode.AUTO,
        audioSilentGraceMs: Long = 2_000L,
    ) = SwitchablePlaybackEngine(
        scope = scope,
        media3Factory = PlaybackEngineCreator { media3 },
        mpvFactory = PlaybackEngineCreator { mpv },
        history = history,
        modeProvider = { mode },
        logger = noOpLogger,
        audioSilentGraceMs = audioSilentGraceMs,
    )

    // ---- 初始选择 ----

    @Test
    fun `plain source selects media3 fast path`() = runTest(dispatcher) {
        val media3 = FakeEngine(EngineKind.MEDIA3)
        val mpv = FakeEngine(EngineKind.MPV)
        val engine = facade(backgroundScope, media3, mpv)
        engine.play(session(source()))
        runCurrent()

        assertEquals(EngineKind.MEDIA3, engine.kind)
        assertEquals(session(source()), media3.playedSession)
        assertNull(mpv.playedSession)
    }

    @Test
    fun `dts source selects mpv directly`() = runTest(dispatcher) {
        val media3 = FakeEngine(EngineKind.MEDIA3)
        val mpv = FakeEngine(EngineKind.MPV)
        val history = InMemoryEnginePreferenceHistory()
        history.recordMedia3Failure(CompatibilitySignature.from(source(container = "mpegts")).key)
        val engine = facade(backgroundScope, media3, mpv, history)
        engine.play(session(source(container = "mpegts")))
        runCurrent()

        assertEquals(EngineKind.MPV, engine.kind)
        assertEquals(session(source(container = "mpegts")), mpv.playedSession)
        assertNull(media3.playedSession)
    }

    // ---- 错误降级 ----

    @Test
    fun `decoder error falls back to mpv at current position and records signature`() = runTest(dispatcher) {
        val media3 = FakeEngine(EngineKind.MEDIA3)
        val mpv = FakeEngine(EngineKind.MPV)
        val history = InMemoryEnginePreferenceHistory()
        val engine = facade(backgroundScope, media3, mpv, history)
        val s = session(source())
        engine.play(s)
        runCurrent()

        // Media3 播放到 42s 后报解码器错误
        media3.updateState { it.copy(positionMs = 42_000, error = PlaybackError(PlaybackError.Code.DECODER_ERROR)) }
        // 注意：backgroundScope 的任务不会被 advanceUntilIdle 驱动，必须用 runCurrent
        runCurrent()

        assertEquals(EngineKind.MPV, engine.kind)
        // 同位置重播
        assertEquals(42_000L, mpv.playedSession?.startPositionMs)
        assertEquals(s.itemId, mpv.playedSession?.itemId)
        // 指纹入历史：后续同签名直接 mpv
        assertTrue(CompatibilitySignature.from(s.source).key in history.mpvPreferredSignatures())
        // Media3 已停止释放
        assertTrue(media3.stopped)
        assertTrue(media3.released)
        // switching 状态回落
        assertFalse(engine.switching.value)
    }

    @Test
    fun `network error does not fall back`() = runTest(dispatcher) {
        val media3 = FakeEngine(EngineKind.MEDIA3)
        val mpv = FakeEngine(EngineKind.MPV)
        val engine = facade(backgroundScope, media3, mpv)
        engine.play(session(source()))
        runCurrent()

        media3.updateState { it.copy(error = PlaybackError(PlaybackError.Code.NETWORK_TIMEOUT)) }
        runCurrent()

        assertEquals(EngineKind.MEDIA3, engine.kind)
        assertNull(mpv.playedSession)
        assertEquals(PlaybackError.Code.NETWORK_TIMEOUT, engine.uiState.value.error?.code)
    }

    @Test
    fun `explicit media3 mode does not fall back`() = runTest(dispatcher) {
        val media3 = FakeEngine(EngineKind.MEDIA3)
        val mpv = FakeEngine(EngineKind.MPV)
        val engine = facade(backgroundScope, media3, mpv, mode = PlaybackEngineMode.MEDIA3)
        engine.play(session(source()))
        runCurrent()

        media3.updateState { it.copy(error = PlaybackError(PlaybackError.Code.DECODER_ERROR)) }
        runCurrent()

        assertEquals(EngineKind.MEDIA3, engine.kind)
        assertNull(mpv.playedSession)
    }

    @Test
    fun `mpv failure does not loop back`() = runTest(dispatcher) {
        val media3 = FakeEngine(EngineKind.MEDIA3)
        val mpv = FakeEngine(EngineKind.MPV)
        val engine = facade(backgroundScope, media3, mpv)
        engine.play(session(source()))
        runCurrent()

        media3.updateState { it.copy(positionMs = 10_000, error = PlaybackError(PlaybackError.Code.UNKNOWN)) }
        runCurrent()
        assertEquals(EngineKind.MPV, engine.kind)

        // mpv 自身也失败：错误透传，不再切回/循环
        val mpvInstance = mpv
        mpvInstance.updateState { it.copy(error = PlaybackError(PlaybackError.Code.DECODER_ERROR)) }
        runCurrent()

        assertEquals(EngineKind.MPV, engine.kind)
        assertEquals(PlaybackError.Code.DECODER_ERROR, engine.uiState.value.error?.code)
        assertNull(media3.playedSession?.startPositionMs) // 未再次 play media3
    }

    // ---- 无声降级 ----

    @Test
    fun `audio silent after grace falls back to mpv`() = runTest(dispatcher) {
        val media3 = FakeEngine(EngineKind.MEDIA3)
        val mpv = FakeEngine(EngineKind.MPV)
        val engine = facade(backgroundScope, media3, mpv, audioSilentGraceMs = 1_000L)
        engine.play(session(source()))
        runCurrent()

        // 有音轨、无音频输出信号、正在播放
        media3.updateState {
            it.copy(
                isPlaying = true,
                positionMs = 5_000,
                audioTracks = listOf(com.mediahub.model.AudioTrack(index = 0, codec = "dts")),
            )
        }
        // 宽限期内 audioFormatMime 出现 → 不降级
        media3.updateState { it.copy(audioFormatMime = "audio/raw") }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(EngineKind.MEDIA3, engine.kind)

        // 再次变为无声并持续超过宽限期 → 降级
        media3.updateState { it.copy(audioFormatMime = null) }
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(EngineKind.MPV, engine.kind)
        assertEquals(5_000L, mpv.playedSession?.startPositionMs)
    }

    // ---- 控制委托与 Surface ----

    @Test
    fun `controls delegate to current engine and stop returns final progress`() = runTest(dispatcher) {
        val media3 = FakeEngine(EngineKind.MEDIA3)
        val mpv = FakeEngine(EngineKind.MPV)
        val engine = facade(backgroundScope, media3, mpv)
        engine.play(session(source()))
        runCurrent()

        engine.seekTo(1_000)
        engine.setSpeed(2f)
        engine.togglePlayPause()
        assertEquals(1_000L, media3.seekedTo)
        assertEquals(2f, media3.speedSet)
        assertTrue(media3.toggled)

        media3.updateState { it.copy(positionMs = 7_000) }
        runCurrent()
        engine.stop()
        assertEquals(7_000L, media3.finalProgressOnStop?.positionMs)
    }

    // ---- Fake ----

    private class FakeEngine(override val kind: EngineKind) : PlaybackEnginePort {
        val ui = MutableStateFlow(PlaybackUiState())
        override val uiState: StateFlow<PlaybackUiState> get() = ui
        private val progressFlow = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 1)
        override val progress: SharedFlow<PlaybackProgress> get() = progressFlow
        private val eventsFlow = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
        override val events: kotlinx.coroutines.flow.Flow<PlaybackEvent> get() = eventsFlow

        var playedSession: PlaybackSession? = null
        var seekedTo: Long = -1
        var speedSet = 1f
        var toggled = false
        var stopped = false
        var released = false
        var finalProgressOnStop: PlaybackProgress? = null

        fun updateState(transform: (PlaybackUiState) -> PlaybackUiState) {
            ui.value = transform(ui.value)
        }

        override val subtitleCues: StateFlow<androidx.media3.common.text.CueGroup?> = MutableStateFlow(null)
        override val downloadSpeedBps: StateFlow<Long> = MutableStateFlow(0L)
        override fun attachSurface(surface: android.view.Surface?) = Unit
        override fun play(session: PlaybackSession) {
            playedSession = session
        }
        override fun togglePlayPause() { toggled = true }
        override fun seekTo(positionMs: Long) { seekedTo = positionMs }
        override fun setSpeed(speed: Float) { speedSet = speed }
        override fun selectAudioTrack(selection: TrackSelection?) = Unit
        override fun selectSubtitleTrack(selection: TrackSelection?) = Unit
        override fun stop(): PlaybackProgress? {
            stopped = true
            finalProgressOnStop = PlaybackProgress(
                serverId = playedSession?.serverId ?: "",
                itemId = playedSession?.itemId ?: "",
                positionMs = ui.value.positionMs,
                durationMs = ui.value.durationMs,
                isPaused = false,
                updatedAtEpochMs = 0L,
            )
            return finalProgressOnStop
        }
        override fun release() { released = true }
    }
}
