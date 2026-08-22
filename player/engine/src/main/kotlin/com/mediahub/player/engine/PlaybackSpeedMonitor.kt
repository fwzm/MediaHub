package com.mediahub.player.engine

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 下载速度监控（Media3 TransferListener，Item 9）。
 *
 * 统计最近约 1 秒窗口内传输的字节数换算成 B/s，供 Overlay 展示真实媒体下载速度。
 * 由引擎每秒进度循环调用 [tick] 衰减：无新字节到达约 1.5s 后归零，避免缓冲/暂停时残留旧速率。
 */
class PlaybackSpeedMonitor : TransferListener {

    private val _bytesPerSecond = MutableStateFlow(0L)
    val bytesPerSecond: StateFlow<Long> = _bytesPerSecond.asStateFlow()

    private var windowBytes = 0L
    private var windowStartMs = 0L
    private var lastTransferMs = 0L

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        windowBytes = 0L
        windowStartMs = SystemClock.elapsedRealtime()
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        val now = SystemClock.elapsedRealtime()
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

    /** 每秒由引擎进度循环调用：无新字节则衰减为 0。 */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (lastTransferMs != 0L && now - lastTransferMs >= IDLE_RESET_MS) {
            _bytesPerSecond.value = 0L
        }
    }

    /** 新播放开始时清零。 */
    fun reset() {
        _bytesPerSecond.value = 0L
        windowBytes = 0L
        windowStartMs = 0L
        lastTransferMs = 0L
    }

    private companion object {
        const val SPEED_WINDOW_MS = 1_000L
        const val IDLE_RESET_MS = 1_500L
    }
}
