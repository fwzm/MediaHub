package com.mediahub.feature.player

import com.mediahub.player.engine.PlaybackUiState

/** Platform boundary; no service, notification or background playback ownership. */
internal interface ForegroundMediaSessionPort {
    fun setCallback(callback: ((MediaKeyAction) -> Unit)?)
    fun setActive(active: Boolean)
    fun publish(state: PlaybackUiState, title: String?)
    fun close()
}

/** One foreground route owns its session and rejects late or background callbacks. */
internal class ForegroundMediaSessionController(
    private val port: ForegroundMediaSessionPort,
    private val onAction: (MediaKeyAction) -> Unit,
) {
    private var foreground = false
    private var closed = false
    init {
        port.setCallback { action -> if (!closed && foreground) onAction(action) }
    }
    fun update(state: PlaybackUiState, title: String?, foreground: Boolean) {
        if (closed) return
        this.foreground = foreground
        if (foreground) port.publish(state, title)
        port.setActive(foreground)
    }
    fun close() {
        if (closed) return
        closed = true
        foreground = false
        try {
            port.setActive(false)
            port.setCallback(null)
        } finally { port.close() }
    }
}
