package com.mediahub.player.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.OkHttpClient

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

        // Emby Direct Stream 常重定向到 HTTP 直链（HTTPS→307→HTTP IP→对象存储），
        // redirect 由 OkHttp 原生跟随（跨协议不受限，受平台 cleartext 策略约束）。
        // 凭据隔离：media3 DefaultHttpDataSource 的手动 redirect 循环会把 DataSpec
        // 全部请求头原样发给每一跳（1.5.1 无剥离逻辑，回归测试已复现泄漏），
        // 因此必须走 OkHttp + [OriginScopedCredentialInterceptor]（Phase 1B-2.2/ADR-030）：
        // Emby 长期凭据只发给原始 origin，跨 origin 跳一律剥离。
        val okHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor(OriginScopedCredentialInterceptor())
            .build()
        val httpDataSourceFactory: DataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(USER_AGENT)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(mediaCacheProvider.cache)
            .setUpstreamDataSourceFactory(dataSourceFactory)

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

    private companion object {
        const val USER_AGENT = "MediaHub"
    }
}
