package com.mediahub.player.engine

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
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
@OptIn(ExperimentalCoroutinesApi::class)
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
        trackSelector.init(object : TrackSelector.InvalidationListener {
            override fun onTrackSelectionsInvalidated(parameters: androidx.media3.common.TrackSelectionParameters?) = Unit
        }, DefaultBandwidthMeter.Builder(context).build())
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

    @After fun releaseEngine() { engine.release() }

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

    @Test
    fun `off then external re-enables real selector and confirms actual target format`() = runTest {
        engine.play(session())
        fake.currentTracks() // real selector maps its text renderer
        engine.selectSubtitleTrack(null)
        assertTrue(selector.parameters.getRendererDisabled(0))
        assertTrue(engine.loadExternalSubtitle(subtitle()))
        assertFalse(selector.parameters.getRendererDisabled(0))
        val id = fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations.single().id
        assertTrue(fake.currentTracks().groups.any { group -> (0 until group.length).any { group.getTrackFormat(it).id == id && group.isTrackSelected(it) } })
    }

    @Test
    fun `embedded then external clears real legacy override instead of replaying embedded`() = runTest {
        engine.play(session()); fake.currentTracks()
        engine.selectSubtitleTrack(TrackSelection(0, 0))
        val embedded = selector.currentMappedTrackInfo!!.getTrackGroups(0)
        assertTrue(selector.parameters.hasSelectionOverride(0, embedded))
        assertTrue(engine.loadExternalSubtitle(subtitle()))
        assertFalse(selector.parameters.hasSelectionOverride(0, embedded))
        val id = fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations.single().id
        assertTrue(fake.currentTracks().groups.any { group -> (0 until group.length).any { group.getTrackFormat(it).id == id && group.isTrackSelected(it) } })
    }

    @Test
    fun `configuration acceptance without prepared target never reports success`() = runTest {
        engine.play(session())
        fake.state.noPreparedSubtitleTracks = true
        assertFalse(engine.loadExternalSubtitle(subtitle()))
        assertEquals(2000L, testScheduler.currentTime)
        assertTrue(fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations.isEmpty())
    }

    @Test
    fun `delayed prepared target is selected using real selector with bounded wait`() = runTest {
        engine.play(session())
        fake.state.emptyTrackReads = 2
        assertTrue(engine.loadExternalSubtitle(subtitle()))
        assertTrue(testScheduler.currentTime in 50L..2000L)
    }

    @Test
    fun `unsupported target and wrong or old format identity never confirm`() = runTest {
        engine.play(session())
        fake.state.rejectExternalSupport = true
        assertFalse(engine.loadExternalSubtitle(subtitle()))
        fake.state.rejectExternalSupport = false
        fake.state.wrongExternalFormatId = true
        assertFalse(engine.loadExternalSubtitle(subtitle()))
        assertTrue(fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations.isEmpty())
    }

    @Test
    fun `off during target preparation invalidates old confirmation and prevents late selection`() = runTest {
        engine.play(session())
        fake.state.noPreparedSubtitleTracks = true
        val pending = async { engine.loadExternalSubtitle(subtitle()) }; runCurrent()
        assertEquals(1, fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations.size)
        engine.selectSubtitleTrack(null)
        fake.state.noPreparedSubtitleTracks = false
        assertFalse(pending.await())
        assertTrue(selector.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        assertTrue(fake.currentTracks().groups.none { it.isSelected })
    }

    @Test
    fun `embedded selection during preparation wins over late external target`() = runTest {
        engine.play(session())
        fake.state.noPreparedSubtitleTracks = true
        val pending = async { engine.loadExternalSubtitle(subtitle()) }; runCurrent()
        fake.currentTracks() // mapped embedded group exists before the manual choice
        engine.selectSubtitleTrack(TrackSelection(0, 0))
        fake.state.noPreparedSubtitleTracks = false
        assertFalse(pending.await())
        val tracks = fake.currentTracks()
        assertTrue(tracks.groups.any { group -> (0 until group.length).any {
            group.getTrackFormat(it).id == "embedded" && group.isTrackSelected(it)
        } })
        assertTrue(tracks.groups.none { group -> (0 until group.length).any {
            group.getTrackFormat(it).id != "embedded" && group.isTrackSelected(it)
        } })
    }

    @Test
    fun `cancelled preparation removes pending config and does not report success`() = runTest {
        engine.play(session())
        fake.state.noPreparedSubtitleTracks = true
        val pending = async { engine.loadExternalSubtitle(subtitle()) }; runCurrent()
        assertEquals(1, fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations.size)
        pending.cancel(); runCurrent()
        assertTrue(pending.isCancelled)
        assertTrue(fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations.isEmpty())
    }

    @Test
    fun `new session stop and release each invalidate a pending target`() = runTest {
        engine.play(session())
        fake.state.noPreparedSubtitleTracks = true
        val first = async { engine.loadExternalSubtitle(subtitle()) }; runCurrent()
        engine.play(session("https://media.example/new.mkv"))
        assertFalse(first.await())
        val second = async { engine.loadExternalSubtitle(subtitle()) }; runCurrent()
        engine.stop()
        assertFalse(second.await())
        assertFalse(engine.loadExternalSubtitle(subtitle()))
        engine.play(session())
        val third = async { engine.loadExternalSubtitle(subtitle()) }; runCurrent()
        engine.release()
        assertFalse(third.await())
    }

    @Test
    fun `new external request supersedes pending old identity and keeps only confirmed config`() = runTest {
        engine.play(session()); fake.state.noPreparedSubtitleTracks = true
        val pending = async { engine.loadExternalSubtitle(subtitle()) }; runCurrent()
        fake.state.noPreparedSubtitleTracks = false
        val new = subtitle(uri = "https://media.example/new.srt")
        assertTrue(engine.loadExternalSubtitle(new))
        assertFalse(pending.await())
        val configs = fake.state.lastMediaItem!!.localConfiguration!!.subtitleConfigurations
        assertEquals(listOf(new.uri), configs.map { it.uri.toString() })
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
        var noPreparedSubtitleTracks = false
        var emptyTrackReads = 0
        var rejectExternalSupport = false
        var wrongExternalFormatId = false
    }

    private class FakeExoPlayerHandler(private val trackSelector: DefaultTrackSelector) : InvocationHandler {
        val state = FakeExoPlayerState()

        private val embedded = TrackGroup("embedded", Format.Builder().setId("embedded").setSampleMimeType("application/x-subrip").build())
        private val textRenderer = object : RendererCapabilities {
            override fun getName() = "fixture-text"
            override fun getTrackType() = C.TRACK_TYPE_TEXT
            override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
            override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
                if (state.rejectExternalSupport && format.id != "embedded") C.FORMAT_UNSUPPORTED_TYPE else C.FORMAT_HANDLED)
        }
        fun currentTracks(): Tracks {
            if (state.emptyTrackReads-- > 0) return Tracks.EMPTY
            val configurations = state.lastMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty()
            val external = if (state.noPreparedSubtitleTracks) emptyList() else configurations.map { config ->
                TrackGroup("group-${config.id}", Format.Builder().setId(if (state.wrongExternalFormatId) "old-format" else config.id)
                    .setSampleMimeType(config.mimeType).setLanguage(config.language).setSelectionFlags(config.selectionFlags).build())
            }
            // Real ExoPlayer activates application parameters on its playback thread before
            // selecting tracks (Media3 1.11 separates applicationParameters/playerParameters).
            trackSelector.onParametersActivated(trackSelector.parameters)
            val result = trackSelector.selectTracks(arrayOf(textRenderer), TrackGroupArray(*((listOf(embedded) + external).toTypedArray())),
                MediaSource.MediaPeriodId("fixture-period"), Timeline.EMPTY)
            trackSelector.onSelectionActivated(result.info)
            return result.tracks
        }

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "getTrackSelector" -> return trackSelector
                "getCurrentTracks" -> return currentTracks()
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
