package com.mediahub.player.engine

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/** ExoPlayer 与会话隔离 MediaSource 的构建工厂。 */
@OptIn(UnstableApi::class)
class PlayerFactory(
    private val context: Context,
    private val mediaCacheProvider: MediaCacheProvider,
) {
    fun create(): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val trackSelector = DefaultTrackSelector(context)
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .build()
    }

    /** 每个 MediaSource 捕获自己的请求上下文，禁止跨会话读取全局 Header。 */
    fun createMediaSource(
        mediaItem: MediaItem,
        requestContext: PlaybackRequestContext,
    ): MediaSource {
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(mediaCacheProvider.cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
        val isolatedFactory = HeaderAwareDataSourceFactory(cacheDataSourceFactory, requestContext)
        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(isolatedFactory)
            .createMediaSource(mediaItem)
    }
}
