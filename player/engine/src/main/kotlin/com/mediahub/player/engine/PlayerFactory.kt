package com.mediahub.player.engine

import android.content.Context
import com.mediahub.core.network.OriginScopedCredentialInterceptor
import com.mediahub.core.network.StartupNetworkEventListener
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
    /** M0-A（ADR-045）：与 API / 图片共用同一 UA 策略。 */
    private val userAgentPolicy: com.mediahub.core.network.UserAgentPolicy =
        com.mediahub.core.network.UserAgentPolicy(),
    private val serverIdFor: com.mediahub.core.network.ServerIdResolver =
        com.mediahub.core.network.ServerIdResolver { null },
) {

    /**
     * @param userAgent M0-A（ADR-045）：本次播放会话应使用的 UA（由上层按服务器作用域解析）。
     *   默认值保持与历史一致，避免未经配置的调用方行为跳变。
     */
    fun create(
        headersHolder: PlaybackHeadersHolder,
        speedMonitor: PlaybackSpeedMonitor,
        userAgent: String = USER_AGENT,
    ): ExoPlayer {
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
            // M0-A（ADR-045）：媒体流每一跳统一 UA；只写 UA，凭据剥离仍由上一行负责。
            .addNetworkInterceptor(
                com.mediahub.core.network.UserAgentInterceptor(userAgentPolicy, serverIdFor),
            )
            .eventListenerFactory(StartupNetworkEventListener.FACTORY)
            .connectionPool(
                okhttp3.ConnectionPool(5, 5, java.util.concurrent.TimeUnit.MINUTES)
            )
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .build()
        val httpDataSourceFactory: DataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(userAgent)
            .setTransferListener(speedMonitor)
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
