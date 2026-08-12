package com.mediahub.player.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * ExoPlayer 构建工厂：
 * - 解码器回退（硬解失败自动软解）；
 * - 默认轨道选择器；
 * - 媒体源工厂：Cache 缓存层 + 每次播放的请求头注入（[PlaybackHeadersHolder]）。
 */
@OptIn(UnstableApi::class)
class PlayerFactory(
    private val context: Context,
    private val mediaCacheProvider: MediaCacheProvider,
) {

    fun create(headersHolder: PlaybackHeadersHolder): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(mediaCacheProvider.cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))

        val headerAwareFactory = HeaderAwareDataSourceFactory(cacheDataSourceFactory) {
            headersHolder.headers
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(headerAwareFactory)

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}
