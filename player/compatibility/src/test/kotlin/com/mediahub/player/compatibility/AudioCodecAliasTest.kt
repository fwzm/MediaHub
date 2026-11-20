package com.mediahub.player.compatibility

import com.mediahub.model.HdrType
import com.mediahub.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round 28 / audit #63 切片：音频编码归一化精度。
 *
 * 旧实现用 `norm.contains(alias)` 做子串匹配，导致：
 * - "eac3" 先命中 AC3 的别名 "ac3"（EAC3 在枚举里排后面）→ EAC3 素材被当成 AC3；
 * - "dts-hd" 先命中 DTS 的别名 "dts" → DTS-HD 素材被当成 DTS；
 * 于是"设备只支持 AC3/DTS"的机器会被误判为可直接播放，实际无声。
 */
class AudioCodecAliasTest {

    @Test
    fun `eac3 tokens resolve to eac3 not ac3`() {
        assertEquals(AudioCodec.EAC3, AudioCodec.fromCodecName("eac3"))
        assertEquals(AudioCodec.EAC3, AudioCodec.fromCodecName("EC3"))
        assertEquals(AudioCodec.EAC3, AudioCodec.fromCodecName("ec-3"))
        assertEquals(AudioCodec.EAC3, AudioCodec.fromCodecName("EAC3 Atmos"))
    }

    @Test
    fun `dolby digital plus spellings resolve to eac3`() {
        assertEquals(AudioCodec.EAC3, AudioCodec.fromCodecName("ddp"))
        assertEquals(AudioCodec.EAC3, AudioCodec.fromCodecName("DD+"))
        assertEquals(AudioCodec.EAC3, AudioCodec.fromCodecName("ddp5.1"))
    }

    @Test
    fun `dts hd variants resolve to dts hd not dts`() {
        assertEquals(AudioCodec.DTS_HD, AudioCodec.fromCodecName("dts-hd"))
        assertEquals(AudioCodec.DTS_HD, AudioCodec.fromCodecName("dtshd"))
        assertEquals(AudioCodec.DTS_HD, AudioCodec.fromCodecName("DTS-HD MA"))
        assertEquals(AudioCodec.DTS_HD, AudioCodec.fromCodecName("dts-hd.ma.5.1"))
        assertEquals(AudioCodec.DTS, AudioCodec.fromCodecName("dts"))
        assertEquals(AudioCodec.DTS, AudioCodec.fromCodecName("DTS 5.1"))
    }

    @Test
    fun `truehd and mlp resolve to truehd`() {
        assertEquals(AudioCodec.TRUEHD, AudioCodec.fromCodecName("truehd"))
        assertEquals(AudioCodec.TRUEHD, AudioCodec.fromCodecName("mlp"))
        assertEquals(AudioCodec.TRUEHD, AudioCodec.fromCodecName("TrueHD Atmos"))
    }

    @Test
    fun `plain ac3 stays ac3 and unknown stays null`() {
        assertEquals(AudioCodec.AC3, AudioCodec.fromCodecName("ac3"))
        assertEquals(AudioCodec.AC3, AudioCodec.fromCodecName("AC-3"))
        assertNull(AudioCodec.fromCodecName(null))
        assertNull(AudioCodec.fromCodecName("  "))
    }

    @Test
    fun `eac3 media on ac3 only device requires audio transcode`() {
        val media = MediaInfo(container = "mkv", videoCodec = "h264", audioCodec = "eac3")
        val device = DeviceCapabilities(
            videoCodecs = setOf(VideoCodecCapability(VideoCodec.H264)),
            audioCodecs = setOf(AudioCodec.AAC, AudioCodec.AC3),
            hdrSupported = setOf(HdrType.HDR10),
        )
        val result = PlaybackCompatibilityEvaluator.evaluate(media, device, UserPreferences())
        assertEquals(PlaybackDecision.DIRECT_STREAM, result.decision)
    }

    @Test
    fun `dts hd media on dts only device requires audio transcode`() {
        val media = MediaInfo(container = "mkv", videoCodec = "h264", audioCodec = "dts-hd")
        val device = DeviceCapabilities(
            videoCodecs = setOf(VideoCodecCapability(VideoCodec.H264)),
            audioCodecs = setOf(AudioCodec.AAC, AudioCodec.DTS),
            hdrSupported = setOf(HdrType.HDR10),
        )
        val result = PlaybackCompatibilityEvaluator.evaluate(media, device, UserPreferences())
        assertEquals(PlaybackDecision.DIRECT_STREAM, result.decision)
    }
}
