package com.mediahub.player.mpv

import com.mediahub.player.engine.TrackSelection
import org.junit.Assert.*
import org.junit.Test

class MpvAudioTrackContractTest {
    private fun rows() = listOf(
        mapOf("id" to "7", "type" to "video", "selected" to "yes"),
        mapOf("id" to "41", "type" to "audio", "lang" to "eng", "codec" to "ac3", "selected" to "yes",
            "default" to "yes", "demux-channel-count" to "6", "demux-samplerate" to "48000"),
        mapOf("id" to "13", "type" to "sub", "selected" to "no"),
        mapOf("id" to "91", "type" to "audio", "lang" to "jpn", "codec" to "aac", "selected" to "yes",
            "default" to "no", "demux-channel-count" to "2", "demux-samplerate" to "44100"),
    )

    @Test fun selectedAudioRequiresNativeAidAndSelectedFlagToAgree() {
        val mapped = MpvTrackMapper.mapAudioTracks(rows(), nativeAid = "91")
        assertEquals(listOf(0, 1), mapped.map { it.index })
        assertEquals(listOf(false, true), mapped.map { it.isSelected })
        assertEquals(6, mapped[0].channels)
        assertEquals(48_000, mapped[0].sampleRate)
        assertTrue(mapped[0].isDefault)
        assertFalse(mapped[1].isDefault)

        val stale = rows().map { it.toMutableMap().apply { if (this["type"] == "audio") this["selected"] = "no" } }
        assertTrue(MpvTrackMapper.mapAudioTracks(stale, nativeAid = "91").none { it.isSelected })
    }

    @Test fun nativeAidUsesContainerIdWhileUiUsesAudioOrdinal() {
        val mapped = MpvTrackMapper.mapAudioTracks(rows(), nativeAid = "41")
        assertEquals(TrackSelection(0, 0), mapped.first { it.isSelected }.let { TrackSelection(it.index, 0) })
        assertEquals("91", MpvTrackMapper.nativeAudioId(rows(), TrackSelection(1, 0)))
    }
}
