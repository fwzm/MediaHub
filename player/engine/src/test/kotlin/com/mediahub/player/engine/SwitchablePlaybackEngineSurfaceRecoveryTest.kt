package com.mediahub.player.engine

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.text.CueGroup
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.PlaybackError
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.model.SubtitleStyle
import com.mediahub.player.compatibility.DevicePlaybackAssessment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 缺陷 47：共享纹理失败时先摘除/重挂 Surface 并原位重播；
 * 同一 session 只重绑一次，仍失败才降级 mpv。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwitchablePlaybackEngineSurfaceRecoveryTest {

    private val dispatcher = StandardTestDispatcher()
    private val surface = Surface(SurfaceTexture(0))

    @After
    fun tearDown() {
        surface.release()
    }

    @Test
    fun `surface failure reattaches once and replays at the same position`() = runTest(dispatcher) {
        val media3 = RecordingEngine(EngineKind.MEDIA3)
        val mpv = RecordingEngine(EngineKind.MPV)
        val engine = facade(backgroundScope, media3, mpv)
        engine.play(session())
        runCurrent()
        engine.attachSurface(surface)
        media3.updateState {
            it.copy(positionMs = 12_000, playWhenReady = true, error = PlaybackError(PlaybackError.Code.SURFACE_ERROR))
        }
        runCurrent()

        advanceTimeBy(121)
        runCurrent()

        // 初始 attach(surface) + 恢复时的 attach(null) / attach(surface)
        assertEquals(listOf(surface, null, surface), media3.surfaceHistory)
        assertEquals(12_000L, media3.seekedTo)
        assertEquals(true, media3.playWhenReadySet)
        assertEquals(EngineKind.MEDIA3, engine.kind)

        // 第二次表面错误：不再重试，降级 mpv 并以当前位置起播。
        media3.updateState {
            it.copy(positionMs = 13_000, error = PlaybackError(PlaybackError.Code.SURFACE_ERROR))
        }
        runCurrent()
        assertEquals(EngineKind.MPV, engine.kind)
        assertEquals(13_000L, mpv.playedSession?.startPositionMs)
    }

    @Test
    fun `surface failure without an attached surface falls back to mpv`() = runTest(dispatcher) {
        val media3 = RecordingEngine(EngineKind.MEDIA3)
        val mpv = RecordingEngine(EngineKind.MPV)
        val engine = facade(backgroundScope, media3, mpv)
        engine.play(session())
        runCurrent()

        media3.updateState { it.copy(positionMs = 4_000, error = PlaybackError(PlaybackError.Code.SURFACE_ERROR)) }
        runCurrent()

        assertEquals(EngineKind.MPV, engine.kind)
        assertTrue(media3.surfaceHistory.isEmpty())
    }

    private fun session() = PlaybackSession(
        serverId = "srv-1",
        itemId = "item-1",
        itemTitle = "电影",
        source = PlaybackSource(url = "http://media/stream", videoCodec = "h264", audioCodec = "aac"),
    )

    private fun facade(scope: CoroutineScope, media3: RecordingEngine, mpv: RecordingEngine) =
        SwitchablePlaybackEngine(
            scope = scope,
            media3Factory = PlaybackEngineCreator { media3 },
            mpvFactory = PlaybackEngineCreator { mpv },
            history = InMemoryEnginePreferenceHistory(),
            modeProvider = { PlaybackEngineMode.AUTO },
            logger = object : Logger {
                override fun d(tag: LogTag, message: String) = Unit
                override fun i(tag: LogTag, message: String) = Unit
                override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
                override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
            },
            audioSilentGraceMs = 60_000L,
            deviceAssessmentProvider = { DevicePlaybackAssessment.UNKNOWN },
        )

    private class RecordingEngine(override val kind: EngineKind) : PlaybackEnginePort {
        private val ui = MutableStateFlow(PlaybackUiState())
        override val uiState: StateFlow<PlaybackUiState> get() = ui
        private val progressFlow = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 1)
        override val progress: SharedFlow<PlaybackProgress> get() = progressFlow
        override val events: Flow<PlaybackEventMessage> = MutableSharedFlow()
        override val subtitleCues: StateFlow<CueGroup?> = MutableStateFlow(null)
        override val downloadSpeedBps: StateFlow<Long> = MutableStateFlow(0L)
        override val audioBands: StateFlow<com.mediahub.player.engine.AudioBandLevels?> = MutableStateFlow(null)
        override val subtitleCapabilities: SubtitleCapabilities =
            SubtitleCapabilities(externalLoad = false, offsetAdjust = false)

        val surfaceHistory = mutableListOf<Surface?>()
        var seekedTo = -1L
        var playWhenReadySet: Boolean? = null
        var playedSession: PlaybackSession? = null

        fun updateState(transform: (PlaybackUiState) -> PlaybackUiState) { ui.value = transform(ui.value) }

        override fun attachSurface(surface: Surface?) { surfaceHistory += surface }
        override fun play(session: PlaybackSession) { playedSession = session }
        override fun togglePlayPause() = Unit
        override fun setPlayWhenReady(play: Boolean) { playWhenReadySet = play }
        override fun seekTo(positionMs: Long, mode: SeekMode) { seekedTo = positionMs }
        override fun setSpeed(speed: Float) = Unit
        override fun selectAudioTrack(selection: TrackSelection?) = true
        override fun selectSubtitleTrack(selection: TrackSelection?) = true
        override fun setSubtitlePresentation(style: SubtitleStyle, sizeSp: Int) = Unit
        override fun stop(): PlaybackProgress? = null
        override fun release() = Unit
    }
}
