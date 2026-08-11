package com.mediahub.player.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 播放缓存（Media3 SimpleCache），与元数据缓存（Room）/ 图片缓存（Coil）完全分离。
 * 缓存内容为媒体分片，不落任何敏感信息（URL/Token 不入库）。
 */
@OptIn(UnstableApi::class)
class MediaCacheProvider(
    context: Context,
    val maxSizeBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val cacheDir = File(context.cacheDir, "playback_cache")
    private val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)

    val cache: SimpleCache by lazy {
        SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(maxSizeBytes), databaseProvider)
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024 // 512MB
    }
}
