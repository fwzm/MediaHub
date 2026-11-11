package com.mediahub.player.compatibility

import com.mediahub.model.HdrType
import com.mediahub.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备能力证据评估（ADR-042）：核心纪律是 UNKNOWN ≠ SUPPORTED ≠ UNSUPPORTED。
 * 采集器覆盖不到的编码只能记 UNKNOWN，绝不能因为"集合里没有"就断言设备不支持。
 */
class DevicePlaybackAssessorTest {

    private fun source(
        video: String? = "hevc",
        audio: String? = "aac",
        hdr: HdrType = HdrType.NONE,
        width: Int? = 1920,
        height: Int? = 1080,
    ) = PlaybackSource(
        url = "http://media/stream",
        videoCodec = video,
        audioCodec = audio,
        hdrType = hdr,
        width = width,
        height = height,
    )

    private fun device(
        video: Set<VideoCodecCapability> = setOf(VideoCodecCapability(VideoCodec.HEVC)),
        audio: Set<AudioCodec> = setOf(AudioCodec.AAC),
        hdr: Set<HdrType> = emptySet(),
    ) = DeviceCapabilities(videoCodecs = video, audioCodecs = audio, hdrSupported = hdr)

    @Test
    fun `supported when device enumerates the codecs`() {
        val result = DevicePlaybackAssessor.assess(source(), device())
        assertEquals(CapabilityEvidence.SUPPORTED, result.video.evidence)
        assertEquals(CapabilityEvidence.SUPPORTED, result.audio.evidence)
    }

    @Test
    fun `unsupported when device enumerates but lacks the video codec`() {
        val result = DevicePlaybackAssessor.assess(
            source(video = "hevc"),
            device(video = setOf(VideoCodecCapability(VideoCodec.H264))),
        )
        assertEquals(CapabilityEvidence.UNSUPPORTED, result.video.evidence)
    }

    @Test
    fun `unsupported when resolution exceeds the device decoder limits`() {
        val result = DevicePlaybackAssessor.assess(
            source(video = "hevc", width = 3840, height = 2160),
            device(video = setOf(VideoCodecCapability(VideoCodec.HEVC, maxWidth = 1920, maxHeight = 1080))),
        )
        assertEquals(CapabilityEvidence.UNSUPPORTED, result.video.evidence)
    }

    @Test
    fun `unknown when the media does not declare a codec`() {
        val result = DevicePlaybackAssessor.assess(source(video = null, audio = null), device())
        assertEquals(CapabilityEvidence.UNKNOWN, result.video.evidence)
        assertEquals(CapabilityEvidence.UNKNOWN, result.audio.evidence)
    }

    @Test
    fun `unknown when device enumeration produced nothing`() {
        val result = DevicePlaybackAssessor.assess(
            source(),
            device(video = emptySet(), audio = emptySet()),
        )
        assertEquals(CapabilityEvidence.UNKNOWN, result.video.evidence)
        assertEquals(CapabilityEvidence.UNKNOWN, result.audio.evidence)
    }

    @Test
    fun `unknown for codecs the enumerator does not cover instead of unsupported`() {
        // TrueHD/MLP 没有 MIME 映射，采集器永远不会报出它——这不是"设备不支持"的证据。
        val result = DevicePlaybackAssessor.assess(source(audio = "truehd"), device())
        assertEquals(CapabilityEvidence.UNKNOWN, result.audio.evidence)
        assertTrue(result.audio.reason.contains("未覆盖"))
    }

    @Test
    fun `dts hd is resolved by longest alias so dts does not shadow it`() {
        val onlyDts = DevicePlaybackAssessor.assess(source(audio = "dts-hd"), device(audio = setOf(AudioCodec.DTS)))
        assertEquals(CapabilityEvidence.UNSUPPORTED, onlyDts.audio.evidence)

        val both = DevicePlaybackAssessor.assess(
            source(audio = "dts-hd"),
            device(audio = setOf(AudioCodec.DTS, AudioCodec.DTS_HD)),
        )
        assertEquals(CapabilityEvidence.SUPPORTED, both.audio.evidence)
    }

    @Test
    fun `unknown when an unrecognized codec string is provided`() {
        val result = DevicePlaybackAssessor.assess(source(video = "totally-unknown-codec"), device())
        assertEquals(CapabilityEvidence.UNKNOWN, result.video.evidence)
    }

    @Test
    fun `hdr evidence distinguishes unknown from unsupported`() {
        val unknown = DevicePlaybackAssessor.assess(source(hdr = HdrType.HDR10), device(hdr = emptySet()))
        assertEquals(CapabilityEvidence.UNKNOWN, unknown.hdr.evidence)

        val unsupported = DevicePlaybackAssessor.assess(source(hdr = HdrType.HDR10), device(hdr = setOf(HdrType.HLG)))
        assertEquals(CapabilityEvidence.UNSUPPORTED, unsupported.hdr.evidence)

        val supported = DevicePlaybackAssessor.assess(source(hdr = HdrType.HDR10), device(hdr = setOf(HdrType.HDR10)))
        assertEquals(CapabilityEvidence.SUPPORTED, supported.hdr.evidence)
    }

    @Test
    fun `non hdr source needs no hdr capability`() {
        val result = DevicePlaybackAssessor.assess(source(hdr = HdrType.NONE), device(hdr = emptySet()))
        assertEquals(CapabilityEvidence.SUPPORTED, result.hdr.evidence)
        assertEquals(CapabilityEvidence.SUPPORTED, result.dolbyVision.evidence)
    }

    @Test
    fun `dolby vision support never claims profile conversion ability`() {
        val result = DevicePlaybackAssessor.assess(
            source(hdr = HdrType.DOLBY_VISION),
            device(hdr = setOf(HdrType.DOLBY_VISION)),
        )
        assertEquals(CapabilityEvidence.SUPPORTED, result.dolbyVision.evidence)
        // 必须显式声明这是近似判据，且不代表 Profile 5/7 或 7→8.1 转换能力已验证。
        assertTrue(result.dolbyVision.reason.contains("近似"))
        assertTrue(result.dolbyVision.reason.contains("Profile"))

        val noDv = DevicePlaybackAssessor.assess(
            source(hdr = HdrType.DOLBY_VISION),
            device(hdr = setOf(HdrType.HDR10)),
        )
        assertEquals(CapabilityEvidence.UNSUPPORTED, noDv.dolbyVision.evidence)
    }

    @Test
    fun `trace metadata never contains urls or credentials`() {
        val metadata = DevicePlaybackAssessor.assess(source(), device()).traceMetadata()
        assertEquals("SUPPORTED", metadata["deviceVideoEvidence"])
        assertEquals("SUPPORTED", metadata["deviceAudioEvidence"])
        val joined = metadata.entries.joinToString("|") { it.key + "=" + it.value }
        assertTrue(!joined.contains("http"))
        assertTrue(!joined.contains("tok", ignoreCase = true))
    }
}
