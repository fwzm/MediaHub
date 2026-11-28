package com.mediahub.player.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺陷 7（播放音频偏小）：多声道片源在立体声输出上做下混时，
 * mpv 默认不做归一化，中置/环绕能量会被压低，表现为"声音偏小"。
 *
 * 修复不是抬高音量上限，而是在**已知多声道编码**上启用
 * `audio-normalize-downmix`（该选项已在 libmpv 1.0.0 字符串表中取证）。
 * 立体声编码保持不动，避免无差别改变现有音量。
 */
class MpvAudioGainPolicyTest {

    @Test
    fun `multichannel codecs request downmix normalization`() {
        listOf("ac3", "eac3", "dts", "dts-hd", "dts-hd ma", "truehd", "mlp", "flac", "pcm_bluray")
            .forEach { assertTrue(it, MpvAudioGainPolicy.shouldNormalizeDownmix(it)) }
    }

    @Test
    fun `stereo codecs keep the existing gain path`() {
        listOf("aac", "mp3", "opus", "vorbis", null, "  ")
            .forEach { assertFalse(it ?: "null", MpvAudioGainPolicy.shouldNormalizeDownmix(it)) }
    }

    @Test
    fun `codec matching is case and whitespace insensitive`() {
        assertTrue(MpvAudioGainPolicy.shouldNormalizeDownmix("  E-AC3 "))
        assertTrue(MpvAudioGainPolicy.shouldNormalizeDownmix("DTS-HD MA"))
    }
}
