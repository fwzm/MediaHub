package com.mediahub.player.engine

import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackProgressReason
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CriticalPlaybackEventQueueTest {
    @Test
    fun `queued critical events are neither conflated nor dropped`() = runBlocking {
        val queue = CriticalPlaybackEventQueue()
        val reasons = listOf(
            PlaybackProgressReason.PLAY,
            PlaybackProgressReason.PAUSE,
            PlaybackProgressReason.SEEK,
            PlaybackProgressReason.PLAY,
            PlaybackProgressReason.END,
        )
        reasons.forEachIndexed { index, reason ->
            queue.offer(
                PlaybackProgressEvent(
                    PlaybackProgress("server", "item", index.toLong(), 10L, false, index.toLong()),
                    reason,
                )
            )
        }

        assertEquals(reasons, queue.events.take(reasons.size).toList().map { it.reason })
    }
}
