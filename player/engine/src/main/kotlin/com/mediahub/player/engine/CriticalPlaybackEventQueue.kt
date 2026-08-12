package com.mediahub.player.engine

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** 关键播放事件不可 conflate/drop；无界 Channel 保持产生顺序直到消费者处理。 */
internal class CriticalPlaybackEventQueue {
    private val channel = Channel<PlaybackProgressEvent>(Channel.UNLIMITED)
    val events: Flow<PlaybackProgressEvent> = channel.receiveAsFlow()

    fun offer(event: PlaybackProgressEvent) {
        check(channel.trySend(event).isSuccess) { "关键播放事件队列已关闭" }
    }

    fun close() = channel.close()
}
