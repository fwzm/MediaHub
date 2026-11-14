package com.mediahub.player.engine

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 下载速度监控（Media3 TransferListener，Item 9）。
 *
 * 统计最近约 1 秒窗口内传输的字节数换算成 B/s，供 Overlay 展示真实媒体下载速度。
 * 由引擎每秒进度循环调用 [tick] 衰减：无新字节到达约 1.5s 后归零，避免缓冲/暂停时残留旧速率。
 *
 * 条目 19：同时监听 [CacheDataSource] 的缓存读取。命中断点缓存区间的 seek 会进入
 * 短暂 buffering 且网络速度为 0，此时 UI 不应显示「0KB/s + 转圈」的网络加载态。
 */
class PlaybackSpeedMonitor(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) : TransferListener, CacheDataSource.EventListener {

    private val _bytesPerSecond = MutableStateFlow(0L)
    val bytesPerSecond: StateFlow<Long> = _bytesPerSecond.asStateFlow()

    /** 最近 1 秒从本地缓存读取的速率（B/s）。 */
    private val _cacheBytesPerSecond = MutableStateFlow(0L)
    val cacheBytesPerSecond: StateFlow<Long> = _cacheBytesPerSecond.asStateFlow()

    /** 当前是否正在从本地缓存读取（缓存 seek 期间为 true）。 */
    private val _isReadingFromCache = MutableStateFlow(false)
    val isReadingFromCache: StateFlow<Boolean> = _isReadingFromCache.asStateFlow()

    private var windowBytes = 0L
    private var windowStartMs = 0L
    private var lastTransferMs = 0L

    private var totalCachedBytesRead = 0L
    private var cacheWindowBytes = 0L
    private var cacheWindowStartMs = 0L
    private var lastCacheReadMs = 0L

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        windowBytes = 0L
        windowStartMs = nowMs()
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        val now = nowMs()
        if (windowStartMs == 0L) windowStartMs = now
        windowBytes += bytesTransferred
        lastTransferMs = now
        val elapsed = now - windowStartMs
        if (elapsed >= SPEED_WINDOW_MS) {
            _bytesPerSecond.value = windowBytes * 1_000L / elapsed.coerceAtLeast(1L)
            windowBytes = 0L
            windowStartMs = now
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        // 传输结束不立即清零（分段/重定向会频繁触发），交给 tick 衰减。
    }

    /** CacheDataSource 回调：cachedBytesRead 是本次数据源的累计值，需取增量。 */
    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
        val delta = (cachedBytesRead - totalCachedBytesRead).coerceAtLeast(0L)
        totalCachedBytesRead = cachedBytesRead
        if (delta <= 0L) return
        val now = nowMs()
        if (cacheWindowStartMs == 0L) cacheWindowStartMs = now
        cacheWindowBytes += delta
        lastCacheReadMs = now
        _isReadingFromCache.value = true
        val elapsed = now - cacheWindowStartMs
        if (elapsed >= SPEED_WINDOW_MS) {
            _cacheBytesPerSecond.value = cacheWindowBytes * 1_000L / elapsed.coerceAtLeast(1L)
            cacheWindowBytes = 0L
            cacheWindowStartMs = now
        }
    }

    override fun onCacheIgnored(reason: Int) = Unit

    /** 每秒由引擎进度循环调用：无新字节则衰减为 0。 */
    fun tick() {
        val now = nowMs()
        if (lastTransferMs != 0L && now - lastTransferMs >= IDLE_RESET_MS) {
            _bytesPerSecond.value = 0L
        }
        if (lastCacheReadMs != 0L && now - lastCacheReadMs >= IDLE_RESET_MS) {
            _cacheBytesPerSecond.value = 0L
            _isReadingFromCache.value = false
            cacheWindowBytes = 0L
            cacheWindowStartMs = 0L
        }
    }

    /** 新播放开始时清零。 */
    fun reset() {
        _bytesPerSecond.value = 0L
        _cacheBytesPerSecond.value = 0L
        _isReadingFromCache.value = false
        windowBytes = 0L
        windowStartMs = 0L
        lastTransferMs = 0L
        totalCachedBytesRead = 0L
        cacheWindowBytes = 0L
        cacheWindowStartMs = 0L
        lastCacheReadMs = 0L
    }

    private companion object {
        const val SPEED_WINDOW_MS = 1_000L
        const val IDLE_RESET_MS = 1_500L
    }
}
