package com.mediahub.player.engine

import com.mediahub.model.AudioTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputWarningTest {
    private val playing = PlaybackUiState(
        isPlaying = true, audioTracks = listOf(AudioTrack(index = 0, codec = "ac3", isSelected = true)),
    )

    @Test fun `unobserved output does not diagnose unsupported AC3`() {
        assertFalse(playing.shouldWarnAboutAudioOutput())
    }

    @Test fun `confirmed output suppresses warning even without a MIME mapping`() {
        assertFalse(playing.copy(audioOutputStatus = AudioOutputStatus.ACTIVE).shouldWarnAboutAudioOutput())
    }

    @Test fun `actual output failure warns only during active non buffering playback`() {
        val failed = playing.copy(audioOutputStatus = AudioOutputStatus.UNAVAILABLE)
        assertTrue(failed.shouldWarnAboutAudioOutput())
        assertFalse(failed.copy(isBuffering = true).shouldWarnAboutAudioOutput())
        assertFalse(failed.copy(isPlaying = false).shouldWarnAboutAudioOutput())
        assertFalse(failed.copy(isEnded = true).shouldWarnAboutAudioOutput())
        assertFalse(failed.copy(audioTracks = emptyList()).shouldWarnAboutAudioOutput())
    }
}
