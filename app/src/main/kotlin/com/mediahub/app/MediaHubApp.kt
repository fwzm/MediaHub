package com.mediahub.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.mediahub.app.image.ProviderImageAuthInterceptor
import com.mediahub.app.image.ProviderImageAuthStore
import com.mediahub.core.network.OriginScopedCredentialInterceptor
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import okhttp3.OkHttpClient

/**
 * 全局 ImageLoader（Phase 1B-2.3 Artwork Pipeline；1G-A 泛化，ADR-039）：
 * - 图片请求经 OkHttp，命中 known origin（Emby/Jellyfin/…）由 ProviderImageAuthStore
 *   经各 Provider 的 ProviderImageAuthContributor 注入认证头（Token 不进 URL）；
 * - 跨 origin 重定向剥离凭据（ADR-030，与播放器同一套红线）；
 * - 磁盘缓存独立于播放缓存（cacheDir/image_cache，256MB LRU，对齐 MediaCacheProvider 先例）；
 * - respectCacheHeaders(false)：忽略服务端 Cache-Control，Coil 磁盘缓存自治（鉴权响应无稳定缓存头）。
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

    private fun imageHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(ProviderImageAuthInterceptor(imageAuthStore::headersForUrl))
            .addNetworkInterceptor(OriginScopedCredentialInterceptor())
            // M0-A（ADR-045）：图片请求与 API / 媒体流使用同一 UA 策略。
            .addNetworkInterceptor(
                com.mediahub.core.network.UserAgentInterceptor(userAgentPolicy, serverIdFor),
            )
            .build()

    private companion object {
        const val IMAGE_DISK_CACHE_BYTES = 256L * 1024 * 1024
    }
}
