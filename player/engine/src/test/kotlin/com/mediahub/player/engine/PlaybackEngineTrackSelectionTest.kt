package com.mediahub.player.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual engine entry points, real Media3 mapping and selector parameters; no renderer-index stubs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEngineTrackSelectionTest {
    private class Fixture(scope: CoroutineScope) : InvocationHandler {
        private val fixtureContext = requireNotNull(RuntimeEnvironment.getApplication())
        val selector = DefaultTrackSelector(fixtureContext).apply {
            init(object : TrackSelector.InvalidationListener {
                override fun onTrackSelectionsInvalidated(parameters: androidx.media3.common.TrackSelectionParameters?) = Unit
            }, DefaultBandwidthMeter.Builder(fixtureContext).build())
        }
        private fun format(id: String, mime: String, bitrate: Int = 128_000) = Format.Builder()
            .setId(id).setLabel(id).setLanguage("eng").setSampleMimeType(mime).setAverageBitrate(bitrate).build()
        val ac3 = TrackGroup("ac3", format("ac3-one", "audio/ac3"))
        val aac = TrackGroup("aac", format("aac-low", "audio/mp4a-latm"), format("aac-high", "audio/mp4a-latm", 256_000))
        val text = TrackGroup("text", format("text-one", "text/vtt"), format("text-two", "text/vtt"))
        private val video = TrackGroup("video", format("video", "video/avc"))
        private fun renderer(name: String, type: Int, mime: String) = object : RendererCapabilities {
            override fun getName() = name
            override fun getTrackType() = type
            override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
            override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
                if (format.sampleMimeType == mime) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)
        }
        // AUDIO(type=1) lives at renderer0 and2; renderer1 is VIDEO. Keep both audio renderers.
        private val renderers = arrayOf(renderer("ac3", C.TRACK_TYPE_AUDIO, "audio/ac3"),
            renderer("video", C.TRACK_TYPE_VIDEO, "video/avc"),
            renderer("aac", C.TRACK_TYPE_AUDIO, "audio/mp4a-latm"),
            renderer("text", C.TRACK_TYPE_TEXT, "text/vtt"))
        private val listeners = mutableListOf<Player.Listener>()
        private var tracks = Tracks.EMPTY
        private var playIntent = false
        private val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java), this) as ExoPlayer
        val engine = PlaybackEngine(player, PlaybackHeadersHolder(), Quiet, scope, PlaybackSpeedMonitor())
        fun publish() {
            selector.onParametersActivated(selector.parameters)
            val result = selector.selectTracks(renderers, TrackGroupArray(video, ac3, aac, text),
                MediaSource.MediaPeriodId("controlled-period"), Timeline.EMPTY)
            selector.onSelectionActivated(result.info)
            tracks = result.tracks
            listeners.toList().forEach { it.onTracksChanged(tracks) }
        }
        fun close() { engine.release(); selector.release() }
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getTrackSelector" -> selector
            "addListener" -> { listeners += args!![0] as Player.Listener; null }
            "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
            "getCurrentTracks" -> tracks
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            "getPlaybackState" -> Player.STATE_READY
            "getDuration" -> 60_000L
            "getPlayWhenReady" -> playIntent
            "setPlayWhenReady" -> { playIntent = args!![0] as Boolean; null }
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 1f
                else -> null
            }
        }
    }
    @Test fun `engine selects second native track on the second audio renderer and preserves text`() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.publish()
            val state = f.engine.uiState.value
            val subtitle = state.trackRowMap.subtitleFor(1)!!
            val audio = state.audioTracks.single { it.title == "aac-high" }
            val address = state.trackRowMap.audioFor(audio.index)!!
            assertEquals(1, address.groupIndex); assertEquals(1, address.trackIndex)
            f.engine.selectSubtitleTrack(subtitle)
            f.engine.selectAudioTrack(address)
            val params = f.selector.parameters
            assertEquals(listOf(1), params.overrides[f.aac]!!.trackIndices)
            assertEquals(listOf(1), params.overrides[f.text]!!.trackIndices)
            assertFalse(params.getRendererDisabled(1))
            assertFalse(params.getRendererDisabled(2))
            assertTrue(params.getRendererDisabled(0))
            assertFalse(params.getRendererDisabled(3))
            val nativeGroups = f.selector.currentMappedTrackInfo!!.getTrackGroups(2)
            assertTrue(params.getSelectionOverride(2, nativeGroups)!!.containsTrack(1))
        } finally { f.close() }
    }
    @Test fun `new track snapshot rejects an old row before any selector mutation`() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.publish(); val old = f.engine.uiState.value.trackRowMap.audioFor(2)!!
            f.publish()
            assertFalse(f.engine.isTrackSelectionCurrent(old))
            val before = f.selector.parameters
            f.engine.selectAudioTrack(old)
            assertEquals(before, f.selector.parameters)
            f.engine.selectAudioTrack(f.engine.uiState.value.trackRowMap.audioFor(2))
            assertEquals(listOf(1), f.selector.parameters.overrides[f.aac]!!.trackIndices)
        } finally { f.close() }
    }
    @Test fun `off clears text overrides and a fresh row restores text without losing audio`() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.publish(); val map = f.engine.uiState.value.trackRowMap
            f.engine.selectAudioTrack(map.audioFor(2)); f.engine.selectSubtitleTrack(map.subtitleFor(1))
            f.engine.selectSubtitleTrack(null)
            assertTrue(f.selector.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            assertFalse(f.selector.parameters.overrides.containsKey(f.text))
            assertTrue(f.selector.parameters.overrides.containsKey(f.aac))
            f.publish()
            f.engine.selectSubtitleTrack(f.engine.uiState.value.trackRowMap.subtitleFor(1))
            assertFalse(f.selector.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            assertEquals(listOf(1), f.selector.parameters.overrides[f.text]!!.trackIndices)
        } finally { f.close() }
    }
    @Test fun `out of range and released snapshot choices do not change parameters`() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.publish(); val token = f.engine.uiState.value.trackRowMap.snapshotToken
            val before = f.selector.parameters
            for (bad in listOf(TrackSelection(-1, 0, token), TrackSelection(0, -1, token),
                TrackSelection(99, 0, token), TrackSelection(1, 99, token))) f.engine.selectAudioTrack(bad)
            assertEquals(before, f.selector.parameters)
            val row = f.engine.uiState.value.trackRowMap.audioFor(2)!!
            f.engine.release(); assertFalse(f.engine.isTrackSelectionCurrent(row))
            f.engine.selectAudioTrack(row); assertEquals(before, f.selector.parameters)
        } finally { f.close() }
    }
    private object Quiet : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
}
