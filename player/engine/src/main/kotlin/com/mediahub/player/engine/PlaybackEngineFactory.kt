package com.mediahub.player.engine

import com.mediahub.core.logging.Logger
import kotlinx.coroutines.CoroutineScope

/**
 * 引擎工厂：每次 create 创建**独立的请求头上下文**（ADR-018），
 * 绑定到调用方协程作用域；不同引擎/预加载/字幕请求互不污染。
 */
class PlaybackEngineFactory(
    private val playerFactory: PlayerFactory,
    private val logger: Logger,
) : PlaybackEngineCreator {
    override fun create(scope: CoroutineScope): PlaybackEnginePort {
        val headersHolder = PlaybackHeadersHolder()
        return PlaybackEngine(playerFactory.create(headersHolder), headersHolder, logger, scope)
    }
}
