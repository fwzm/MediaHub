package com.mediahub.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放信息观测字段的归一化契约（引擎侧补充观测，面板据此显示真实解码器/渲染器/码率/帧率）。
 *
 * 三条不伪造成本最低的原则：
 * 1. 空字符串不是证据 —— codecs 为空白时回退 MIME，MIME 也空白则保持 UNKNOWN(null)。
 * 2. 0 / 负数不是证据 —— Media3 对未知码率报 Format.NO_VALUE(-1)，帧率报 0，一律归一为 null。
 * 3. 未观测不等于观测为空 —— 传入 null/空白时保持原值，绝不把已知值清掉（mpv 属性回调可能先到空串）。
 */
class PlaybackObservedInfoTest {

    @Test
    fun `video format records codec bitrate and frame rate`() {
        val observed = PlaybackObservedInfo().withVideoFormat(
            codecs = "avc1.640028",
            mimeType = "video/avc",
            bitrate = 12_000_000,
            frameRate = 23.976f,
        )

        assertEquals("avc1.640028", observed.videoCodec)
        assertEquals(12_000_000L, observed.videoBitrateBps)
        assertEquals(23.976f, observed.frameRate)
        assertNull(observed.audioCodec)
        assertNull(observed.videoDecoder)
        assertNull(observed.audioDecoder)
        assertNull(observed.hwdec)
    }

    @Test
    fun `blank codecs fall back to mime and non positive numbers stay unknown`() {
        val fromMime = PlaybackObservedInfo().withVideoFormat(
            codecs = "   ",
            mimeType = "video/hevc",
            bitrate = -1,
            frameRate = 0f,
        )
        assertEquals("video/hevc", fromMime.videoCodec)
        assertNull(fromMime.videoBitrateBps)
        assertNull(fromMime.frameRate)

        val nothing = PlaybackObservedInfo().withVideoFormat(null, null, 0, 0f)
        assertNull(nothing.videoCodec)
        assertNull(nothing.videoBitrateBps)
        assertNull(nothing.frameRate)
    }

    @Test
    fun `audio format does not clobber video observation`() {
        val observed = PlaybackObservedInfo()
            .withVideoFormat("hevc", "video/hevc", 20_000_000, 60f)
            .withAudioFormat("mp4a.40.2", "audio/mp4a-latm", 192_000)

        assertEquals("hevc", observed.videoCodec)
        assertEquals(20_000_000L, observed.videoBitrateBps)
        assertEquals(60f, observed.frameRate)
        assertEquals("mp4a.40.2", observed.audioCodec)
        assertEquals(192_000L, observed.audioBitrateBps)
    }

    @Test
    fun `decoder hwdec and renderer only record non blank evidence`() {
        val set = PlaybackObservedInfo()
            .withVideoDecoder("c2.qti.avc.decoder")
            .withAudioDecoder("c2.qti.aac.decoder")
            .withHwdec("mediacodec")
            .withRenderer("SurfaceView")
        assertEquals("c2.qti.avc.decoder", set.videoDecoder)
        assertEquals("c2.qti.aac.decoder", set.audioDecoder)
        assertEquals("mediacodec", set.hwdec)
        assertEquals("SurfaceView", set.renderer)

        // 未观测保持原值（不能把已经拿到的解码器信息清掉）
        val kept = set.withVideoDecoder(null).withAudioDecoder("  ").withHwdec(null).withRenderer("")
        assertEquals("c2.qti.avc.decoder", kept.videoDecoder)
        assertEquals("c2.qti.aac.decoder", kept.audioDecoder)
        assertEquals("mediacodec", kept.hwdec)
        assertEquals("SurfaceView", kept.renderer)
    }

    @Test
    fun `default ui state reports nothing observed`() {
        val observed = PlaybackUiState().observed
        assertNull(observed.videoCodec)
        assertNull(observed.audioCodec)
        assertNull(observed.videoBitrateBps)
        assertNull(observed.audioBitrateBps)
        assertNull(observed.frameRate)
        assertNull(observed.videoDecoder)
        assertNull(observed.audioDecoder)
        assertNull(observed.hwdec)
        assertNull(observed.renderer)
    }
}
