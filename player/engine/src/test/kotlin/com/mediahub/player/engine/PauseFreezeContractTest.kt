package com.mediahub.player.engine

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.PlaybackSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * G3 · 暂停后位置不得继续推进（引擎层确定性复现）。
 *
 * 真机缺陷（EXECUTED_FAIL）：点击一次应用内暂停控件后，播放位置仍由 29s 推进到 44s。
 * 已排除「双击被识别为暂停/继续」的扰动。本测试在引擎层把「暂停语义」钉死：
 * 暂停后，进度循环**不得**再让 positionMs 前进。
 *
 * 用 fake ExoPlayer（Proxy）+ 受控时钟：position 只在播放中由 fake 推进，
 * 暂停（playWhenReady=false）后 fake 不再推进，若引擎仍报告更大位置，即说明
 * 引擎在暂停态读取/推进了错误的位置源。
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PauseFreezeContractTest {

    private class FakeState {
        var playWhenReady = false
        var playing = false
        var positionMs = 0L
        var durationMs = 90_000L
        val seekTargets = mutableListOf<Long>()
    }

    private fun engine(dispatcher: kotlinx.coroutines.test.TestDispatcher, state: FakeState): PlaybackEngine {
        val context = RuntimeEnvironment.getApplication()
        val selector = DefaultTrackSelector(context)
        selector.init(object : TrackSelector.InvalidationListener {
            override fun onTrackSelectionsInvalidated(parameters: androidx.media3.common.TrackSelectionParameters?) = Unit
        }, DefaultBandwidthMeter.Builder(context).build())
        val handler = object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
                when (method.name) {
                    "getTrackSelector" -> return selector
                    "addListener", "removeListener", "addAnalyticsListener", "removeAnalyticsListener",
                    "setVideoSurface", "setAudioAttributes", "setMediaItem", "prepare",
                    "setPlaybackSpeed", "setVolume" -> return null
                    "getCurrentTracks" -> return androidx.media3.common.Tracks.EMPTY
                    "getCurrentMediaItem" -> return null
                    "play" -> { state.playWhenReady = true; state.playing = true; return null }
                    "pause" -> { state.playWhenReady = false; state.playing = false; return null }
                    "setPlayWhenReady" -> {
                        val v = args?.get(0) as Boolean
                        state.playWhenReady = v; state.playing = v; return null
                    }
                    "getPlayWhenReady" -> return state.playWhenReady
                    "isPlaying" -> return state.playing
                    "getCurrentPosition" -> return state.positionMs
                    "getDuration" -> return state.durationMs
                    "isCurrentMediaItemSeekable" -> return true
                    "seekTo" -> {
                        val target = if (args?.size == 2) args[1] as Long else args?.get(0) as Long
                        state.positionMs = target; state.seekTargets += target; return null
                    }
                    "getPlaybackParameters" -> return PlaybackParameters(1f)
                    "getAudioSessionId" -> return 0
                    "getVolume" -> return 1f
                    "getAudioFormat" -> return null
                    "release" -> return null
                    else -> return when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        Float::class.javaPrimitiveType -> 0f
                        else -> null
                    }
                }
            }
        }
        val proxy = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java), handler,
        ) as ExoPlayer
        return PlaybackEngine(
            player = proxy,
            headersHolder = PlaybackHeadersHolder(),
            logger = object : Logger {
                override fun d(tag: LogTag, message: String) = Unit
                override fun i(tag: LogTag, message: String) = Unit
                override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
                override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
            },
            scope = CoroutineScope(dispatcher),
            speedMonitor = PlaybackSpeedMonitor(),
        )
    }

    private fun session() = PlaybackSession(
        serverId = "srv-g3",
        itemId = "item-g3",
        itemTitle = "G3 夹具",
        source = PlaybackSource(url = "https://media.example/g3.mp4", mimeType = "video/mp4"),
    )

    @Test
    fun `pause freezes UI position and does not keep advancing while paused`() = runTest {
        val state = FakeState().apply { positionMs = 29_000L }
        val engine = engine(StandardTestDispatcher(testScheduler), state)
        try {
            engine.play(session())
            runCurrent()
            // 播放中：位置随 fake 推进（模拟真实解码器前进）
            state.positionMs = 30_000L
            advanceTimeBy(1_100)
            runCurrent()
            val playingPosition = engine.uiState.value.positionMs
            assertTrue("播放中位置必须反映真实进度，实际=${playingPosition}", playingPosition >= 29_000L)

            // 暂停：fake 停在原地（真实 Media3 暂停后 currentPosition 不再增长）
            engine.togglePlayPause()
            runCurrent()
            val pausedAt = engine.uiState.value.positionMs
            assertFalse("暂停后 isPlaying 必须为 false", engine.uiState.value.isPlaying)

            // 等待多个进度循环周期：位置**不得**前进
            repeat(3) { advanceTimeBy(1_100); runCurrent() }
            assertEquals(
                "暂停后位置必须冻结（真机缺陷：29s→44s 说明这里会继续前进）",
                pausedAt,
                engine.uiState.value.positionMs,
            )
        } finally {
            engine.release()
        }
    }

    @Test
    fun `togglePlayPause must use playWhenReady not isPlaying`() = runTest {
        // 真实缺陷（G3 根因之一）：暂停判定用 isPlaying。Media3 在缓冲中 / 未就绪 /
        // 音频焦点短暂丢失时 isPlaying=false 但 playWhenReady=true，
        // 于是「第一次点击本意是暂停」被解释成 resume —— 用户看到「点了暂停，位置还在走」。
        val state = FakeState().apply { playing = false; playWhenReady = false; positionMs = 29_000L }
        val engine = engine(StandardTestDispatcher(testScheduler), state)
        try {
            engine.play(session())
            runCurrent()
            // 模拟 Media3 处于「已请求播放但 isPlaying 仍为 false」的窗口（真实存在：缓冲中/未就绪）
            state.playWhenReady = true
            state.playing = false
            engine.togglePlayPause()
            runCurrent()
            assertEquals(
                "togglePlayPause 必须依据 playWhenReady 判定当前是否应暂停；" +
                    "若依据 isPlaying，在 isPlaying=false 但 playWhenReady=true 的窗口里会错误 resume",
                false,
                state.playWhenReady,
            )
        } finally {
            engine.release()
        }
    }
}
