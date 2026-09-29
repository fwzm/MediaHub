package com.mediahub.player.engine

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.source.TrackGroupArray
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.PlaybackSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Media3 外挂字幕重建（P2 字幕中心切片一）——引擎层 fake 验证：
 * - 重建后 位置/暂停/倍速 保留（fake 在换源时故意丢失状态，验证引擎恢复纪律）；
 * - SubtitleConfiguration mime/uri/id 映射；多次加载累计；
 * - Media3 无偏移 API：setSubtitleOffset 如实拒绝（能力矩阵 externalLoad=true, offsetAdjust=false）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEngineExternalSubtitleTest {

    private lateinit var selector: DefaultTrackSelector
    private lateinit var fake: FakeExoPlayerHandler
    private lateinit var engine: PlaybackEngine
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val trackSelector = DefaultTrackSelector(context).also { selector = it }
        fake = FakeExoPlayerHandler(trackSelector)
        val proxy = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
            fake,
        )
        scope = CoroutineScope(StandardTestDispatcher())
        engine = PlaybackEngine(
            player = proxy as ExoPlayer,
            headersHolder = PlaybackHeadersHolder(),
            logger = NoLogger,
            scope = scope,
            speedMonitor = PlaybackSpeedMonitor(),
        )
    }

    private fun session(url: String = "https://media.example/movie.mkv") = PlaybackSession(
        serverId = "srv-1",
        itemId = "m1",
        itemTitle = "电影",
        source = PlaybackSource(url = url, mimeType = "video/mp4"),
    )

    private fun subtitle(
        mime: String = "application/x-subrip",
        uri: String = "https://media.example/movie.zh.srt",
    ) = ExternalSubtitle(id = uri, name = "movie.zh", uri = uri, mimeType = mime, language = "zh")

    // ---- 能力矩阵（如实自述） ----

    @Test
    fun `capability matrix externalLoad true offsetAdjust false`() {
        assertTrue(engine.subtitleCapabilities.externalLoad)
        assertFalse(engine.subtitleCapabilities.offsetAdjust)
    }

    @Test
    fun `offset request is rejected without any state change`() = runBlocking {
        engine.play(session())
        assertFalse(engine.setSubtitleOffset(500L))
        assertEquals(0L, engine.subtitleOffsetMs)
    }

    @Test
    fun `load before a session exists is rejected`() = runBlocking {
        assertFalse(engine.loadExternalSubtitle(subtitle()))
        assertEquals(0, fake.state.mediaItemCount)
    }

    // ---- 重建保留纪律 ----

    @Test
    fun `rebuild preserves position pause and speed`() = runBlocking {
        engine.play(session())
        // 模拟播放推进 + 用户暂停 + 1.5 倍速
        fake.state.positionMs = 42_000L
        engine.pause()
        engine.setSpeed(1.5f)
        val preparesBefore = fake.state.prepareCount

        val accepted = engine.loadExternalSubtitle(subtitle())

        assertTrue(accepted)
        assertEquals(preparesBefore + 1, fake.state.prepareCount)
        // setMediaItem(item, position)：同调用内完成换源与 seek
        assertEquals(42_000L, fake.state.lastSetWithPositionMs!!)
        // fake 在换源时故意丢失 暂停/倍速（模拟最坏情形），引擎必须恢复快照
        assertFalse("重建后必须恢复暂停态", fake.state.playWhenReady)
        assertEquals("重建后必须恢复倍速", 1.5f, fake.state.speed)
    }

    @Test
    fun `subtitle configuration carries mime uri and id and loads accumulate`() = runBlocking {
        engine.play(session())

        assertTrue(engine.loadExternalSubtitle(subtitle()))
        assertTrue(
            engine.loadExternalSubtitle(
                subtitle(mime = "text/x-ssa", uri = "https://media.example/movie.eng.ass"),
            ),
        )

        val configs = fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations
        assertEquals(2, configs.size)
        val srt = configs[0]
        assertEquals("application/x-subrip", srt.mimeType)
        assertEquals("https://media.example/movie.zh.srt", srt.uri.toString())
        assertEquals("zh", srt.language)
        assertEquals("text/x-ssa", configs[1].mimeType)
    }

    @Test
    fun `unsupported mime and new sessions behave honestly`() = runBlocking {
        engine.play(session())
        val prepares = fake.state.prepareCount

        assertFalse(engine.loadExternalSubtitle(subtitle(mime = "application/x-fantasy")))
        assertEquals(prepares, fake.state.prepareCount) // 无重建

        // 新会话：外挂字幕不跨会话携带
        assertTrue(engine.loadExternalSubtitle(subtitle()))
        assertEquals(1, fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations.size)
        engine.play(session(url = "https://media.example/other.mkv"))
        assertEquals(0, fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations.size)
    }

    private fun installMapping(types: IntArray, groups: Array<TrackGroupArray>) {
        val constructor = MappingTrackSelector.MappedTrackInfo::class.java.declaredConstructors.single()
        constructor.isAccessible = true
        val supports = Array(groups.size) { r -> Array(groups[r].length) { g -> IntArray(groups[r][g].length) { C.FORMAT_HANDLED } } }
        val info = constructor.newInstance(Array(types.size) { "renderer$it" }, types, groups, IntArray(types.size), supports, TrackGroupArray.EMPTY)
        selector.onSelectionActivated(info)
    }

    @Test
    fun `subtitle selection and off use actual renderer index after renderer reorder`() {
        val text = TrackGroupArray(TrackGroup(
            Format.Builder().setSampleMimeType("application/x-subrip").setLanguage("zh").build(),
            Format.Builder().setSampleMimeType("application/x-subrip").setLanguage("en").build(),
        ))
        val video = TrackGroupArray(TrackGroup(Format.Builder().setSampleMimeType("video/avc").build()))
        val audio = TrackGroupArray(TrackGroup(Format.Builder().setSampleMimeType("audio/mp4a-latm").build()))
        installMapping(intArrayOf(C.TRACK_TYPE_TEXT, C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO), arrayOf(text, video, audio))
        engine.selectSubtitleTrack(TrackSelection(0, 1))
        val override = selector.parameters.getSelectionOverride(0, text)!!
        assertEquals(0, override.groupIndex)
        assertTrue(override.tracks.contentEquals(intArrayOf(1)))
        assertFalse(selector.parameters.getRendererDisabled(0))
        assertFalse(selector.parameters.getRendererDisabled(1))
        engine.selectSubtitleTrack(null)
        assertTrue(selector.parameters.getRendererDisabled(0))
        assertFalse(selector.parameters.getRendererDisabled(1))
        engine.selectSubtitleTrack(TrackSelection(0, 0))
        assertFalse(selector.parameters.getRendererDisabled(0))
    }

    @Test
    fun `audio selection validates group and track bounds before selector mutation`() {
        val audio = TrackGroupArray(TrackGroup(Format.Builder().setSampleMimeType("audio/mp4a-latm").build()))
        installMapping(intArrayOf(C.TRACK_TYPE_AUDIO), arrayOf(audio))
        engine.selectAudioTrack(TrackSelection(0, 0))
        val before = selector.parameters
        engine.selectAudioTrack(TrackSelection(-1, 0))
        engine.selectAudioTrack(TrackSelection(0, -1))
        engine.selectAudioTrack(TrackSelection(0, 1))
        engine.selectAudioTrack(TrackSelection(1, 0))
        assertEquals(before, selector.parameters)
    }

    @Test
    fun `missing renderer fails closed and multiple matching renderers resolve type ordinal`() {
        installMapping(intArrayOf(C.TRACK_TYPE_VIDEO), arrayOf(TrackGroupArray.EMPTY))
        val before = selector.parameters
        engine.selectSubtitleTrack(null)
        assertEquals(before, selector.parameters)
        val a = TrackGroupArray(TrackGroup(Format.Builder().setSampleMimeType("application/x-subrip").setLanguage("zh").build()))
        val b = TrackGroupArray(TrackGroup(Format.Builder().setSampleMimeType("application/x-subrip").setLanguage("en").build()))
        installMapping(intArrayOf(C.TRACK_TYPE_TEXT, C.TRACK_TYPE_TEXT), arrayOf(a, b))
        engine.selectSubtitleTrack(TrackSelection(1, 0))
        assertTrue(selector.parameters.getRendererDisabled(0))
        assertFalse(selector.parameters.getRendererDisabled(1))
        assertEquals(0, selector.parameters.getSelectionOverride(1, b)!!.groupIndex)
    }

    // ---- fake：反射 Proxy 实现 ExoPlayer，仅覆盖引擎真实触碰的成员 ----

    private class FakeExoPlayerState {
        var mediaItemCount = 0
        var lastMediaItem: MediaItem? = null
        var lastSetWithPositionMs: Long? = null
        var prepareCount = 0
        var playWhenReady = false
        var speed = 1f
        var positionMs = 0L
        var released = false
    }

    private class FakeExoPlayerHandler(private val trackSelector: TrackSelector) : InvocationHandler {
        val state = FakeExoPlayerState()

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "getTrackSelector" -> return trackSelector
                "setVideoSurface", "setAudioAttributes", "addListener", "removeListener",
                "addAnalyticsListener", "removeAnalyticsListener", "setVolume",
                -> return null

                "play" -> { state.playWhenReady = true; return null }
                "pause" -> { state.playWhenReady = false; return null }

                "setMediaItem" -> {
                    val item = args?.get(0) as MediaItem
                    state.mediaItemCount++
                    state.lastMediaItem = item
                    when (args!!.size) {
                        3 -> state.lastSetWithPositionMs = args[2] as Long
                        2 -> state.lastSetWithPositionMs = args[1] as Long
                        else -> state.lastSetWithPositionMs = null
                    }
                    // 模拟最坏情形：换源丢失暂停态与倍速（真实 Media3 保留，这里验证引擎显式恢复）
                    state.playWhenReady = true
                    state.speed = 1f
                    return null
                }

                "prepare" -> { state.prepareCount++; return null }
                "seekTo" -> { state.positionMs = args?.get(0) as Long; return null }
                "setPlayWhenReady" -> { state.playWhenReady = args?.get(0) as Boolean; return null }
                "getPlayWhenReady" -> return state.playWhenReady
                "setPlaybackSpeed" -> { state.speed = args?.get(0) as Float; return null }
                "setPlaybackParameters" -> {
                    state.speed = (args?.get(0) as PlaybackParameters).speed
                    return null
                }
                "getPlaybackParameters" -> return PlaybackParameters(state.speed)
                "getCurrentPosition" -> return state.positionMs
                "getDuration" -> return 0L
                "isPlaying" -> return false
                "isCurrentMediaItemSeekable" -> return true
                "getAudioSessionId" -> return 0
                "getVolume" -> return 1f
                "release" -> { state.released = true; return null }
                else -> return defaultValue(method.returnType)
            }
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.java -> false
            Int::class.java -> 0
            Long::class.java -> 0L
            Float::class.java -> 0f
            Double::class.java -> 0.0
            else -> null
        }
    }

    private object NoLogger : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
}
