package com.mediahub.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.mediahub.app.image.ProviderImageAuthInterceptor
import com.mediahub.app.image.ProviderImageAuthStore
import com.mediahub.core.network.NetworkExchangeLedger
import com.mediahub.core.network.OriginScopedCredentialInterceptor
import com.mediahub.core.network.ServerIdResolver
import com.mediahub.core.network.UserAgentInterceptor
import com.mediahub.core.network.UserAgentPolicy
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * 全局 ImageLoader（Phase 1B-2.3 Artwork Pipeline；1G-A 泛化，ADR-039）：
 * - 图片请求经 OkHttp，命中 known origin（Emby/Jellyfin/…）由 ProviderImageAuthStore
 *   经各 Provider 的 ProviderImageAuthContributor 注入认证头（Token 不进 URL）；
 * - 跨 origin 重定向剥离凭据（ADR-030，与播放器同一套红线）；
 * - 磁盘缓存独立于播放缓存（cacheDir/image_cache，256MB LRU，对齐 MediaCacheProvider 先例）；
 * - respectCacheHeaders(false)：忽略服务端 Cache-Control，Coil 磁盘缓存自治（鉴权响应无稳定缓存头）。
 *
 * M16-G4 可观测性边界：图片出网边界是 [imageHttpClient]。Coil 内部不经过
 * NetworkExchangeRecorder 的逐交换记账，因此该 client 上**每一次真实出网**
 * 都必须在起点向台账登记 RequestChannel.IMAGE 为不可观测通道（语义是"未知"而非"0"）。
 */
@HiltAndroidApp
class MediaHubApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageAuthStore: ProviderImageAuthStore

    /** M0-A（ADR-045）：全局唯一的 UA 策略（服务器作用域）。 */
    @Inject
    lateinit var userAgentPolicy: com.mediahub.core.network.UserAgentPolicy

    /** M0-A：URL -> serverId 解析（复用图片鉴权作用域，fail closed）。 */
    @Inject
    lateinit var serverIdFor: com.mediahub.core.network.ServerIdResolver

    /** M16：网络交换观测台账（与 Media3 取流路径共用同一实例）。 */
    @Inject
    lateinit var networkLedger: NetworkExchangeLedger

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .callFactory(imageHttpClient())
            .crossfade(true)
            .respectCacheHeaders(false)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .build()

    /** 生产图片 client：注入 Hilt 提供的同一条观测台账。 */
    fun imageHttpClient(): OkHttpClient = imageHttpClient(
        ledger = networkLedger,
        headersForUrl = imageAuthStore::headersForUrl,
        userAgentPolicy = userAgentPolicy,
        serverIdFor = serverIdFor,
    )

    internal companion object {
        const val IMAGE_DISK_CACHE_BYTES = 256L * 1024 * 1024

        /**
         * 图片出网 client（Coil 的全部真实出网都经这里）。
         *
         * 参数全部显式传入，便于在没有 Application 实例（Robolectric
         * `application = Application::class`）的 JVM 测试里驱动**同一条**生产 client。
         *
         * M16-G4：Coil 的取图不经过 HttpClientFactory 的观测链，因此把 IMAGE 通道
         * 登记为**不可观测**（语义是"未知"，不是"0 次请求"）。
         * 该登记在**每个图片 client 的构造点**完成 —— 只要 Coil 真的用这条 chain，
         * partialObservability 就必然为 true，不会出现"看起来全绿其实没算"的假象。
         */
        fun imageHttpClient(
            ledger: NetworkExchangeLedger?,
            headersForUrl: (HttpUrl) -> Map<String, String>?,
            userAgentPolicy: UserAgentPolicy,
            serverIdFor: ServerIdResolver,
        ): OkHttpClient {
            ledger?.recordUnobservable(com.mediahub.core.network.RequestChannel.IMAGE)
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(ProviderImageAuthInterceptor(headersForUrl))
                .addNetworkInterceptor(OriginScopedCredentialInterceptor())
                // M0-A（ADR-045）：图片请求与 API / 媒体流使用同一 UA 策略。
                .addNetworkInterceptor(UserAgentInterceptor(userAgentPolicy, serverIdFor))
                .build()
        }
    }
}
