package com.mediahub.player.engine

import com.mediahub.core.logging.Logger
import kotlinx.coroutines.CoroutineScope

/** 引擎工厂：创建绑定到调用方协程作用域的引擎实例。 */
class PlaybackEngineFactory(
    private val playerFactory: PlayerFactory,
    private val headersHolder: PlaybackHeadersHolder,
    private val logger: Logger,
) {
    fun create(scope: CoroutineScope): PlaybackEngine =
        PlaybackEngine(playerFactory.create(), headersHolder, logger, scope)
}
