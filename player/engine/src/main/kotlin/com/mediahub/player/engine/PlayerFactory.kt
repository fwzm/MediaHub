package com.mediahub.player.engine

import android.content.Context
import com.mediahub.core.network.NetworkExchangeLedger
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
    /**
     * M16-G4：网络交换观测台账（Media3 取流路径的可观测性边界）。
     *
     * 默认 null = 不观测（生产默认路径行为不变）；传入时，**每一次真实取流**
     * （ExoPlayer 打开媒体 DataSource）都会在该起点向台账登记
     * [com.mediahub.core.network.RequestChannel.MEDIA] 为"不可观测通道"，
     * 使 [com.mediahub.core.network.NetworkExchangeSnapshot.partialObservability] 为 true
     * —— 而不是把"没有计数"冒充成"零交换"。
     */
    private val ledger: NetworkExchangeLedger? = null,
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

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(mediaDataSourceFactory(headersHolder, speedMonitor, userAgent))

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    /**
     * 生产媒体 DataSource 链（ExoPlayer 真实取流的唯一入口）。
     *
     * 独立成公开方法是为了让"播放真实取流路径"可被 JVM 测试直接驱动：
     * 测试拿到的是与 [create] **完全相同**的链，而不是另造一条替身链。
     * 接线前后行为不变（同一组 OkHttp / 缓存 / 请求头注入组件）。
     */
    fun mediaDataSourceFactory(
        headersHolder: PlaybackHeadersHolder,
        speedMonitor: PlaybackSpeedMonitor,
        userAgent: String = USER_AGENT,
    ): DataSource.Factory {
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

        // ---- M16-G4：可观测性边界登记（GREEN）----
        //
        // Media3 的取流不经过 HttpClientFactory 的 apiClient/mediaClient 观测链：
        // 它有**自己**的 OkHttpClient（见上）与自己的 DataSource 组装，
        // 因此按交换级记账在这里既不可得、也不该伪造。
        //
        // 语义（见 docs/reviews/m16-s0-metric-definition.md）：
        // 把 MEDIA 通道登记为**不可观测**，使快照的 partialObservability 为 true ——
        // 即"未知"，而**不是**"0 次上游请求"。这一步必须发生在**真实取流起点**，
        // 否则又是一条"API 存在但永远不被调用"的死接线。
        ledger?.recordUnobservable(com.mediahub.core.network.RequestChannel.MEDIA)

        return HeaderAwareDataSourceFactory(cacheDataSourceFactory) {
            headersHolder.headers
        }
    }

    private companion object {
        const val USER_AGENT = "MediaHub"
    }
}
