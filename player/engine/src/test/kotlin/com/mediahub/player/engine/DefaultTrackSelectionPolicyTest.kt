package com.mediahub.player.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 多轨同组默认选轨策略契约（ADR-048）。
 *
 * 断言**真实 [DefaultTrackSelector]** 在 MediaHub 默认参数下的选择结果，钉死策略阶梯：
 * 语言偏好 → 容器 DEFAULT 标记 → 设备支持态 → 码率/声道 → 首轨兜底。
 *
 * 这些用例描述的是生产 `PlayerFactory` 创建的 selector 应有行为；实现前它们应当失败，
 * 因为生产构造点没有把任何语言偏好写进 selector 参数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultTrackSelectionPolicyTest {

    private val appContext: android.content.Context =
        requireNotNull(RuntimeEnvironment.getApplication())

    /**
     * 生产 selector 的构造方式：`PlayerFactory.createTrackSelector()` 使用同一套
     * [TrackPreferencePolicy] 参数；这里只把偏好语言替换成用例给定的语言，
     * 保证断言的是链路本身而不是测试自造的参数。
     */
    private fun newSelector(vararg preferredAudioLanguages: String): DefaultTrackSelector =
        TrackPreferencePolicy.prepare(
            DefaultTrackSelector(appContext).apply {
                init(
                    object : TrackSelector.InvalidationListener {
                        override fun onTrackSelectionsInvalidated(
                            parameters: androidx.media3.common.TrackSelectionParameters?,
                        ) = Unit
                    },
                    DefaultBandwidthMeter.Builder(appContext).build(),
                )
            },
            preferredAudioLanguages.toList(),
        )

    private fun format(
        id: String,
        mime: String,
        language: String? = null,
        bitrate: Int = 128_000,
        selectionFlags: Int = 0,
    ): Format = Format.Builder()
        .setId(id)
        .setLabel(id)
        .setSampleMimeType(mime)
        .setLanguage(language)
        .setAverageBitrate(bitrate)
        .setSelectionFlags(selectionFlags)
        .build()

    private fun audioRenderer() = renderer(
        "audio",
        C.TRACK_TYPE_AUDIO,
        setOf("audio/mp4a-latm", "audio/ac3", "audio/eac3", "audio/opus"),
    )

    private fun renderer(name: String, type: Int, supportedMimes: Set<String>) = object : RendererCapabilities {
        override fun getName() = name
        override fun getTrackType() = type
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
        override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
            if (format.sampleMimeType in supportedMimes) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE,
        )
    }

    private val renderers = arrayOf(
        audioRenderer(),
        renderer("video", C.TRACK_TYPE_VIDEO, setOf("video/avc")),
        renderer("text", C.TRACK_TYPE_TEXT, setOf("text/vtt")),
    )

    private fun select(selector: DefaultTrackSelector, vararg groups: TrackGroup): Tracks {
        selector.onParametersActivated(selector.parameters)
        val result = selector.selectTracks(
            renderers,
            TrackGroupArray(*groups),
            MediaSource.MediaPeriodId("default-policy-period"),
            Timeline.EMPTY,
        )
        selector.onSelectionActivated(result.info)
        return result.tracks
    }

    private fun selectedAudio(tracks: Tracks): List<String> =
        tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group ->
                (0 until group.length)
                    .filter { group.isTrackSelected(it) }
                    .mapNotNull { group.getTrackFormat(it).id }
            }

    @Test
    fun `preferred language outranks the container default flag`() {
        val selector = newSelector("zh")
        val tracks = select(
            selector,
            TrackGroup(
                "eng-default",
                format("audio-eng-default", "audio/mp4a-latm", "eng", 320_000, C.SELECTION_FLAG_DEFAULT),
            ),
            TrackGroup("zho-plain", format("audio-zh", "audio/mp4a-latm", "zho", 96_000)),
        )
        assertEquals(listOf("audio-zh"), selectedAudio(tracks))
    }

    @Test
    fun `container default flag wins when no preferred language matches`() {
        val selector = newSelector("zh")
        val tracks = select(
            selector,
            TrackGroup("fra", format("audio-fra", "audio/mp4a-latm", "fra", 640_000)),
            TrackGroup(
                "eng-default",
                format("audio-eng-default", "audio/mp4a-latm", "eng", 128_000, C.SELECTION_FLAG_DEFAULT),
            ),
        )
        assertEquals(listOf("audio-eng-default"), selectedAudio(tracks))
    }

    @Test
    fun `higher bitrate wins between tracks of the same language`() {
        val selector = newSelector("eng")
        val tracks = select(
            selector,
            TrackGroup(
                "aac",
                format("aac-low", "audio/mp4a-latm", "eng", 128_000),
                format("aac-high", "audio/mp4a-latm", "eng", 256_000),
            ),
        )
        assertEquals(listOf("aac-high"), selectedAudio(tracks))
    }

    @Test
    fun `same language across groups still prefers the higher bitrate track`() {
        val selector = newSelector("eng")
        val tracks = select(
            selector,
            TrackGroup("aac", format("aac-128", "audio/mp4a-latm", "eng", 128_000)),
            TrackGroup("eac3", format("eac3-640", "audio/eac3", "eng", 640_000)),
        )
        assertEquals(listOf("eac3-640"), selectedAudio(tracks))
    }

    @Test
    fun `unsupported track is never selected even when it has the highest bitrate`() {
        val selector = newSelector("eng")
        val tracks = select(
            selector,
            TrackGroup(
                "dts",
                format("dts-hd", "audio/vnd.dts.hd", "eng", 1_500_000, C.SELECTION_FLAG_DEFAULT),
                format("aac-128", "audio/mp4a-latm", "eng", 128_000),
            ),
        )
        assertEquals(listOf("aac-128"), selectedAudio(tracks))
    }

    @Test
    fun `first track is the deterministic fallback when every preference ties`() {
        val selector = newSelector("eng")
        val tracks = select(
            selector,
            TrackGroup(
                "tie",
                format("first", "audio/mp4a-latm", "eng", 128_000),
                format("second", "audio/mp4a-latm", "eng", 128_000),
            ),
        )
        assertEquals(listOf("first"), selectedAudio(tracks))
    }

    @Test
    fun `audio-only stream without video or text still gets one default track`() {
        val selector = newSelector("eng")
        val tracks = select(
            selector,
            TrackGroup("only", format("only-audio", "audio/mp4a-latm", "eng", 128_000)),
        )
        assertEquals(listOf("only-audio"), selectedAudio(tracks))
    }

    @Test
    fun `stream without audio tracks selects no audio track`() {
        val selector = newSelector("eng")
        val tracks = select(
            selector,
            TrackGroup("video", format("video", "video/avc")),
            TrackGroup("text", format("text", "text/vtt", "eng")),
        )
        assertEquals(emptyList<String>(), selectedAudio(tracks))
    }
}
