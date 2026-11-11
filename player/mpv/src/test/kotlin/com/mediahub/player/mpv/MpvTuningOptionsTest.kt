package com.mediahub.player.mpv

import com.mediahub.model.HdrType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2（ADR-044）：mpv 参数配置的纯逻辑与"只下发有证据的选项"契约。
 *
 * 这些测试证明的是**配置层行为**；它不代表真机渲染结果（设备验证本轮 NOT_RUN）。
 */
class MpvTuningOptionsTest {

    @Test
    fun `non hdr source never touches tone mapping`() {
        val options = MpvTuningOptions.build(MpvTuningInput(sourceHdr = HdrType.NONE, deviceSupportsSourceHdr = false))
        assertTrue(options.isEmpty())
    }

    @Test
    fun `hdr source on a non hdr display enables tone mapping`() {
        val options = MpvTuningOptions.build(
            MpvTuningInput(sourceHdr = HdrType.HDR10, deviceSupportsSourceHdr = false),
        )
        assertEquals(
            listOf(
                MpvTuningOption("tone-mapping", "bt.2390"),
                MpvTuningOption("hdr-compute-peak", "yes"),
            ),
            options,
        )
    }

    @Test
    fun `hdr capable display keeps passthrough untouched`() {
        val options = MpvTuningOptions.build(
            MpvTuningInput(sourceHdr = HdrType.HDR10, deviceSupportsSourceHdr = true),
        )
        assertTrue(options.isEmpty())
    }

    @Test
    fun `unknown display capability is never guessed`() {
        val options = MpvTuningOptions.build(
            MpvTuningInput(sourceHdr = HdrType.DOLBY_VISION, deviceSupportsSourceHdr = null),
        )
        assertTrue(options.isEmpty())
    }

    @Test
    fun `downmix normalization is opt in`() {
        assertTrue(MpvTuningOptions.build(MpvTuningInput(normalizeDownmix = false)).isEmpty())
        assertEquals(
            listOf(MpvTuningOption("audio-normalize-downmix", "yes")),
            MpvTuningOptions.build(MpvTuningInput(normalizeDownmix = true)),
        )
    }

    @Test
    fun `volume max is clamped into the supported range`() {
        assertEquals("100", MpvTuningOptions.build(MpvTuningInput(volumeMaxPercent = 10)).single().value)
        assertEquals("1000", MpvTuningOptions.build(MpvTuningInput(volumeMaxPercent = 9_999)).single().value)
        assertEquals("250", MpvTuningOptions.build(MpvTuningInput(volumeMaxPercent = 250)).single().value)
    }

    @Test
    fun `every emitted option is backed by binary evidence`() {
        val all = MpvTuningOptions.build(
            MpvTuningInput(
                sourceHdr = HdrType.HDR10,
                deviceSupportsSourceHdr = false,
                normalizeDownmix = true,
                volumeMaxPercent = 300,
            ),
        )
        assertEquals(4, all.size)
        all.forEach { option ->
            assertTrue("option not evidenced: " + option.name, option.name in MpvTuningOptions.EVIDENCED_OPTIONS)
        }
    }

    @Test
    fun `absent options and uncompiled filters are never emitted`() {
        val all = MpvTuningOptions.build(
            MpvTuningInput(
                sourceHdr = HdrType.HDR10,
                deviceSupportsSourceHdr = false,
                normalizeDownmix = true,
                volumeMaxPercent = 200,
            ),
        )
        val names = all.map { it.name }
        (MpvTuningOptions.ABSENT_OPTIONS + MpvTuningOptions.UNCOMPILED_FILTERS).forEach { forbidden ->
            assertTrue("forbidden option emitted: " + forbidden, names.none { it.contains(forbidden) })
        }
    }

    @Test
    fun `absent and uncompiled sets stay documented`() {
        // 这两个集合是"不可用证据"的固化；任何试图把它们挪进 EVIDENCED 的改动都必须先给出新的产物证据。
        assertTrue("audio-downmix" in MpvTuningOptions.ABSENT_OPTIONS)
        assertTrue("loudnorm" in MpvTuningOptions.UNCOMPILED_FILTERS)
        assertTrue(MpvTuningOptions.ABSENT_OPTIONS.none { it in MpvTuningOptions.EVIDENCED_OPTIONS })
        assertTrue(MpvTuningOptions.UNCOMPILED_FILTERS.none { it in MpvTuningOptions.EVIDENCED_OPTIONS })
    }
}
