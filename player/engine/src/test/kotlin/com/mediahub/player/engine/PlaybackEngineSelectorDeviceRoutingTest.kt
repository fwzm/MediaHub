package com.mediahub.player.engine

import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackSource
import com.mediahub.player.compatibility.AxisAssessment
import com.mediahub.player.compatibility.CapabilityEvidence
import com.mediahub.player.compatibility.DevicePlaybackAssessment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设备证据驱动的引擎选路（ADR-042）。
 *
 * 与 [PlaybackCompatibilityEvaluator] 的分工在此测试中体现：这里只关心"设备能不能解"，
 * 不关心服务端应该 DIRECT_PLAY 还是 TRANSCODE。
 */
class PlaybackEngineSelectorDeviceRoutingTest {

    private fun source(video: String? = "hevc", audio: String? = "aac") = PlaybackSource(
        url = "http://media/stream",
        videoCodec = video,
        audioCodec = audio,
    )

    private fun device(
        video: CapabilityEvidence = CapabilityEvidence.UNKNOWN,
        audio: CapabilityEvidence = CapabilityEvidence.UNKNOWN,
        hdr: CapabilityEvidence = CapabilityEvidence.UNKNOWN,
    ) = DevicePlaybackAssessment(
        video = AxisAssessment(video, "test-video"),
        audio = AxisAssessment(audio, "test-audio"),
        hdr = AxisAssessment(hdr, "test-hdr"),
        dolbyVision = AxisAssessment.unknown("test-dv"),
    )

    @Test
    fun `unsupported device video codec routes to mpv`() {
        val selection = PlaybackEngineSelector.select(
            source(), PlaybackEngineMode.AUTO, emptySet(),
            device = device(video = CapabilityEvidence.UNSUPPORTED),
        )
        assertEquals(EngineKind.MPV, selection.kind)
    }

    @Test
    fun `unsupported device audio codec routes to mpv`() {
        val selection = PlaybackEngineSelector.select(
            source(), PlaybackEngineMode.AUTO, emptySet(),
            device = device(audio = CapabilityEvidence.UNSUPPORTED),
        )
        assertEquals(EngineKind.MPV, selection.kind)
    }

    @Test
    fun `unknown device evidence keeps the media3 fast path`() {
        val selection = PlaybackEngineSelector.select(
            source(), PlaybackEngineMode.AUTO, emptySet(),
            device = device(),
        )
        assertEquals(EngineKind.MEDIA3, selection.kind)
    }

    @Test
    fun `supported device evidence keeps the media3 fast path`() {
        val selection = PlaybackEngineSelector.select(
            source(), PlaybackEngineMode.AUTO, emptySet(),
            device = device(CapabilityEvidence.SUPPORTED, CapabilityEvidence.SUPPORTED),
        )
        assertEquals(EngineKind.MEDIA3, selection.kind)
    }

    @Test
    fun `no device assessment defaults to unknown semantics`() {
        val selection = PlaybackEngineSelector.select(source(), PlaybackEngineMode.AUTO, emptySet())
        assertEquals(EngineKind.MEDIA3, selection.kind)
    }

    @Test
    fun `user forced media3 outranks unsupported device evidence`() {
        val selection = PlaybackEngineSelector.select(
            source(), PlaybackEngineMode.MEDIA3, emptySet(),
            device = device(CapabilityEvidence.UNSUPPORTED, CapabilityEvidence.UNSUPPORTED),
        )
        assertEquals(EngineKind.MEDIA3, selection.kind)
    }

    @Test
    fun `user forced mpv outranks supported device evidence`() {
        val selection = PlaybackEngineSelector.select(
            source(), PlaybackEngineMode.MPV, emptySet(),
            device = device(CapabilityEvidence.SUPPORTED, CapabilityEvidence.SUPPORTED),
        )
        assertEquals(EngineKind.MPV, selection.kind)
    }

    @Test
    fun `historical failure fingerprint still wins over supported device evidence`() {
        val src = source(video = "h264", audio = "aac")
        val key = CompatibilitySignature.from(src).key
        val selection = PlaybackEngineSelector.select(
            src, PlaybackEngineMode.AUTO, setOf(key),
            device = device(CapabilityEvidence.SUPPORTED, CapabilityEvidence.SUPPORTED),
        )
        assertEquals(EngineKind.MPV, selection.kind)
    }

    @Test
    fun `unsupported hdr display does not silently change the engine`() {
        // M2 依赖：在 mpv tone-mapping 能力取证之前，HDR 不参与路由（只作为证据记录）。
        val selection = PlaybackEngineSelector.select(
            source(), PlaybackEngineMode.AUTO, emptySet(),
            device = device(hdr = CapabilityEvidence.UNSUPPORTED),
        )
        assertEquals(EngineKind.MEDIA3, selection.kind)
    }
}
