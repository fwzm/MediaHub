package com.mediahub.player.compatibility

import com.mediahub.model.HdrType
import com.mediahub.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCompatibilityEvaluatorTest {

    private val prefs = UserPreferences()

    private fun device(
        video: Set<VideoCodecCapability> = setOf(
            VideoCodecCapability(VideoCodec.H264),
            VideoCodecCapability(VideoCodec.HEVC, maxWidth = 1920, maxHeight = 1080),
            VideoCodecCapability(VideoCodec.AV1),
            VideoCodecCapability(VideoCodec.VP9),
        ),
        audio: Set<AudioCodec> = setOf(AudioCodec.AAC, AudioCodec.AC3, AudioCodec.EAC3, AudioCodec.FLAC, AudioCodec.OPUS),
        hdr: Set<HdrType> = setOf(HdrType.HDR10),
    ) = DeviceCapabilities(videoCodecs = video, audioCodecs = audio, hdrSupported = hdr)

    @Test
    fun `h264 mp4 aac direct plays`() {
        val media = MediaInfo(container = "mp4", videoCodec = "h264", audioCodec = "aac", width = 1920, height = 1080)
        val result = PlaybackCompatibilityEvaluator.evaluate(media, device(), prefs)
        assertEquals(PlaybackDecision.DIRECT_PLAY, result.decision)
    }

    @Test
    fun `hevc 4k exceeds device capability to transcode`() {
        val media = MediaInfo(container = "mkv", videoCodec = "hevc", width = 3840, height = 2160)
        val result = PlaybackCompatibilityEvaluator.evaluate(media, device(), prefs)
        assertEquals(PlaybackDecision.TRANSCODE, result.decision)
    }

    @Test
    fun `unknown codec to unsupported`() {
        val media = MediaInfo(container = "mkv", videoCodec = "some-future-codec")
        val result = PlaybackCompatibilityEvaluator.evaluate(media, device(), prefs)
        assertEquals(PlaybackDecision.UNSUPPORTED, result.decision)
    }

    @Test
    fun `video ok but unsupported audio to direct stream`() {
        val media = MediaInfo(container = "mkv", videoCodec = "h264", audioCodec = "truehd")
        val result = PlaybackCompatibilityEvaluator.evaluate(
            media,
            device(audio = setOf(AudioCodec.AAC)),
            prefs,
        )
        assertEquals(PlaybackDecision.DIRECT_STREAM, result.decision)
    }

    @Test
    fun `unsupported container with decodable video to direct stream`() {
        val media = MediaInfo(container = "avi", videoCodec = "h264", audioCodec = "aac")
        val result = PlaybackCompatibilityEvaluator.evaluate(media, device(), prefs)
        assertEquals(PlaybackDecision.DIRECT_STREAM, result.decision)
    }

    @Test
    fun `hdr10 on non hdr display to transcode`() {
        val media = MediaInfo(container = "mkv", videoCodec = "hevc", width = 1920, height = 1080, hdrType = HdrType.HDR10)
        val result = PlaybackCompatibilityEvaluator.evaluate(media, device(hdr = emptySet()), prefs)
        assertEquals(PlaybackDecision.TRANSCODE, result.decision)
    }

    @Test
    fun `hevc 10bit without 10bit capability to transcode`() {
        val media = MediaInfo(container = "mkv", videoCodec = "hevc", width = 1920, height = 1080, hdrType = HdrType.HDR10)
        val dev = device(
            video = setOf(
                VideoCodecCapability(VideoCodec.HEVC, maxWidth = 1920, maxHeight = 1080, supports10Bit = false),
            ),
            hdr = emptySet(),
        )
        val result = PlaybackCompatibilityEvaluator.evaluate(media, dev, prefs)
        assertEquals(PlaybackDecision.TRANSCODE, result.decision)
    }

    @Test
    fun `unknown resolution does not block direct play`() {
        val media = MediaInfo(container = "mp4", videoCodec = "avc1", audioCodec = "mp4a")
        val result = PlaybackCompatibilityEvaluator.evaluate(media, device(), prefs)
        assertEquals(PlaybackDecision.DIRECT_PLAY, result.decision)
    }

    @Test
    fun `bitrate above user limit to direct stream`() {
        val media = MediaInfo(container = "mkv", videoCodec = "h264", audioCodec = "aac", bitrate = 40_000_000)
        val limitedPrefs = UserPreferences(maxBitrateBps = 20_000_000)
        val result = PlaybackCompatibilityEvaluator.evaluate(media, device(), limitedPrefs)
        assertEquals(PlaybackDecision.DIRECT_STREAM, result.decision)
    }
}
