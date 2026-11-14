package com.mediahub.feature.player.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PiP 会话状态与 PiP 窗口动作的分发契约（条目 123 / 113）。
 *
 * 真机才能验证「系统真的把窗口画出来」，但「动作有没有送到引擎」这一层
 * 必须能在 JVM 上被证明，否则只能算未接线。
 */
class PictureInPictureSessionTest {

    @Test
    fun `state updates are published to collectors`() {
        val session = PictureInPictureSession()

        session.update { it.copy(playerActive = true, title = "第 1 集", positionMs = 1_000L) }

        assertEquals(true, session.state.value.playerActive)
        assertEquals("第 1 集", session.state.value.title)
        assertEquals(1_000L, session.state.value.positionMs)
    }

    @Test
    fun `dispatch without a registered player handler reports failure`() {
        val session = PictureInPictureSession()

        assertFalse(session.dispatch(PipCommand.TOGGLE_PLAY_PAUSE))
    }

    @Test
    fun `dispatch forwards the command to the registered handler`() {
        val session = PictureInPictureSession()
        val received = mutableListOf<PipCommand>()
        session.setCommandHandler { received += it }

        assertTrue(session.dispatch(PipCommand.FORWARD))
        assertTrue(session.dispatch(PipCommand.TOGGLE_PLAY_PAUSE))
        assertTrue(session.dispatch(PipCommand.REWIND))

        assertEquals(
            listOf(PipCommand.FORWARD, PipCommand.TOGGLE_PLAY_PAUSE, PipCommand.REWIND),
            received,
        )
    }

    @Test
    fun `clearing the handler makes late broadcasts harmless`() {
        val session = PictureInPictureSession()
        var calls = 0
        session.setCommandHandler { calls += 1 }

        session.setCommandHandler(null)

        assertFalse(session.dispatch(PipCommand.REWIND))
        assertEquals(0, calls)
    }

    @Test
    fun `leaving the player clears the session so background entry stops`() {
        val session = PictureInPictureSession()
        session.update {
            it.copy(playerActive = true, isPlaying = true, resolveReady = true)
        }

        session.clearPlayer()

        assertFalse(session.state.value.playerActive)
        assertFalse(
            PictureInPicturePolicy.shouldAutoEnter(
                supported = true,
                autoPictureInPicture = true,
                isPlaying = session.state.value.isPlaying,
                resolveReady = session.state.value.resolveReady,
                alreadyInPictureInPicture = false,
            ),
        )
    }
}
