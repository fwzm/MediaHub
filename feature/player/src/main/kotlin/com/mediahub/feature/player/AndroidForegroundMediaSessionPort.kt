package com.mediahub.feature.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.mediahub.player.engine.PlaybackUiState

/** System media-button routing for either playback engine, owned by the foreground route. */
internal class AndroidForegroundMediaSessionPort(context: Context) : ForegroundMediaSessionPort {
    private val session = MediaSession(context.applicationContext, "MediaHub.Foreground")
    private var receiver: ((MediaKeyAction) -> Unit)? = null

    init {
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { receiver?.invoke(MediaKeyAction.PLAY) }
            override fun onPause() { receiver?.invoke(MediaKeyAction.PAUSE) }
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
                val key = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                } ?: return false
                val action = MediaKeyMapper.actionFor(key.keyCode) ?: return false
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) receiver?.invoke(action)
                return true
            }
        }, Handler(Looper.getMainLooper()))
    }

    override fun setCallback(callback: ((MediaKeyAction) -> Unit)?) { receiver = callback }
    override fun setActive(active: Boolean) { session.isActive = active }
    override fun publish(state: PlaybackUiState, title: String?) {
        val playbackState = when {
            state.error != null -> PlaybackState.STATE_ERROR
            state.isEnded -> PlaybackState.STATE_STOPPED
            !state.playWhenReady -> PlaybackState.STATE_PAUSED
            state.isBuffering -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PLAYING
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(playbackState, state.positionMs.coerceAtLeast(0),
                if (state.isPlaying) state.speed else 0f, SystemClock.elapsedRealtime())
            .build())
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs.coerceAtLeast(0))
            .build())
    }
    override fun close() { receiver = null; session.release() }
}
