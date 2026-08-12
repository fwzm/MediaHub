package com.mediahub.player.engine

import com.mediahub.core.logging.Logger
import kotlinx.coroutines.CoroutineScope

/** 引擎工厂：每次调用获得独立 ExoPlayer 与播放会话状态。 */
class PlaybackEngineFactory(
    private val playerFactory: PlayerFactory,
    private val logger: Logger,
) {
    fun create(scope: CoroutineScope): PlaybackEngine =
        PlaybackEngine(playerFactory.create(), playerFactory, logger, scope)
}
