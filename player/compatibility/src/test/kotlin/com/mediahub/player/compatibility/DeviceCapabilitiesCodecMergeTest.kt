package com.mediahub.player.compatibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MediaCodecList 对同一编码常返回多个解码器（软解 + 硬解 + 不同分辨率档位）。
 * 设备能力快照必须把它们合并成一个"最强能力包络"，否则能力取决于声明顺序。
 */
class DeviceCapabilitiesCodecMergeTest {

    private fun hevc(width: Int, height: Int, tenBit: Boolean = true, hardware: Boolean = true) =
        VideoCodecCapability(
            codec = VideoCodec.HEVC,
            maxWidth = width,
            maxHeight = height,
            supports10Bit = tenBit,
            hardwareAccelerated = hardware,
        )

    @Test
    fun `two decoders for one codec merge into the widest envelope`() {
        val device = DeviceCapabilities(
            videoCodecs = setOf(hevc(1920, 1080), hevc(3840, 2160)),
        )

        val capability = device.videoCapability(VideoCodec.HEVC)

        assertEquals(3840, capability?.maxWidth)
        assertEquals(2160, capability?.maxHeight)
        assertTrue(capability!!.canDecode(3840, 2160, is10Bit = false))
    }

    @Test
    fun `ten bit support survives when the weaker decoder is declared first`() {
        val device = DeviceCapabilities(
            videoCodecs = setOf(
                hevc(3840, 2160, tenBit = false),
                hevc(3840, 2160, tenBit = true),
            ),
        )

        val capability = device.videoCapability(VideoCodec.HEVC)

        assertTrue(capability!!.supports10Bit)
        assertTrue(capability.canDecode(3840, 2160, is10Bit = true))
    }

    @Test
    fun `hardware acceleration is reported when any decoder is hardware backed`() {
        val device = DeviceCapabilities(
            videoCodecs = setOf(
                hevc(3840, 2160, hardware = false),
                hevc(1920, 1080, hardware = true),
            ),
        )

        assertTrue(device.videoCapability(VideoCodec.HEVC)!!.hardwareAccelerated)
    }

    @Test
    fun `software only decoders are not reported as hardware accelerated`() {
        val device = DeviceCapabilities(
            videoCodecs = setOf(
                hevc(3840, 2160, hardware = false),
                hevc(1920, 1080, hardware = false),
            ),
        )

        assertFalse(device.videoCapability(VideoCodec.HEVC)!!.hardwareAccelerated)
    }

    @Test
    fun `unknown codec stays unknown instead of borrowing another envelope`() {
        val device = DeviceCapabilities(
            videoCodecs = setOf(
                hevc(3840, 2160),
                VideoCodecCapability(codec = VideoCodec.H264),
            ),
        )

        assertNull(device.videoCapability(VideoCodec.VP9))
    }

    @Test
    fun `empty capability snapshot stays empty`() {
        val device = DeviceCapabilities()

        assertNull(device.videoCapability(VideoCodec.H264))
        assertTrue(device.videoCodecs.isEmpty())
    }
}
