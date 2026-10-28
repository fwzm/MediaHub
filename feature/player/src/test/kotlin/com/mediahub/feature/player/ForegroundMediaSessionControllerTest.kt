package com.mediahub.feature.player

import com.mediahub.player.engine.PlaybackUiState
import org.junit.Assert.*
import org.junit.Test

class ForegroundMediaSessionControllerTest {
    private class Port : ForegroundMediaSessionPort {
        var receiver: ((MediaKeyAction) -> Unit)? = null
        var activeState = false
        var closes = 0
        val published = mutableListOf<Pair<PlaybackUiState, String?>>()
        override fun setCallback(callback: ((MediaKeyAction) -> Unit)?) { this.receiver = callback }
        override fun setActive(active: Boolean) { this.activeState = active }
        override fun publish(state: PlaybackUiState, title: String?) { published += state to title }
        override fun close() { closes++ }
    }

    @Test fun `foreground binds system callback and publishes real playback state`() {
        val port = Port()
        val owner = ForegroundMediaSessionController(port) {}
        val state = PlaybackUiState(isPlaying = true, positionMs = 12_345, speed = 1.5f)
        owner.update(state, "Controlled title", true)
        assertNotNull(port.receiver)
        assertTrue(port.activeState)
        assertEquals(state to "Controlled title", port.published.last())
        owner.update(state.copy(isPlaying = false, playWhenReady = false), "Controlled title", true)
        assertTrue("Paused foreground session must still receive Play", port.activeState)
        assertFalse(port.published.last().first.playWhenReady)
        owner.close()
    }

    @Test fun `only current foreground owner forwards transport commands`() {
        val port = Port();val actions = mutableListOf<MediaKeyAction>()
        val owner = ForegroundMediaSessionController(port, actions::add)
        owner.update(PlaybackUiState(isPlaying = true), "fixture", true)
        assertNotNull(port.receiver)
        val queued = requireNotNull(port.receiver)
        queued(MediaKeyAction.PAUSE)
        assertEquals(listOf(MediaKeyAction.PAUSE), actions)
        owner.update(PlaybackUiState(), "fixture", false)
        queued(MediaKeyAction.PLAY)
        assertFalse(port.activeState)
        assertEquals(listOf(MediaKeyAction.PAUSE), actions)
        owner.close();queued(MediaKeyAction.TOGGLE)
        assertEquals(listOf(MediaKeyAction.PAUSE), actions)
    }

    @Test fun `disposal is idempotent and cannot reactivate a dead owner`() {
        val port = Port();val actions = mutableListOf<MediaKeyAction>()
        val owner = ForegroundMediaSessionController(port, actions::add)
        owner.update(PlaybackUiState(isPlaying = true), "fixture", true)
        val queued = port.receiver
        owner.close();owner.close()
        owner.update(PlaybackUiState(isPlaying = true), "late", true)
        queued?.invoke(MediaKeyAction.PLAY)
        assertEquals(1, port.closes)
        assertFalse(port.activeState)
        assertNull(port.receiver)
        assertTrue(actions.isEmpty())
    }
}
