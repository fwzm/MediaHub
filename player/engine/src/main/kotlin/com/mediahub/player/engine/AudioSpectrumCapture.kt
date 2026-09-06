package com.mediahub.player.engine

import android.media.audiofx.Visualizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 生产采样后端与可测试控制器之间的最小边界。 */
internal interface AudioSpectrumCapture {
    fun release()
}

internal interface AudioSpectrumCaptureListener {
    fun onFftData(fft: ByteArray, samplingRateMilliHz: Int)
    fun onUnavailable(cause: Throwable)
}

internal fun interface AudioSpectrumCaptureFactory {
    /** 返回 null 表示当前 session、权限或设备不支持频谱采样。 */
    fun create(
        audioSessionId: Int,
        listener: AudioSpectrumCaptureListener,
    ): AudioSpectrumCapture?
}

/** Android Visualizer 的最薄可测试边界，避免 JVM 测试依赖 framework 实例。 */
internal interface VisualizerPlatform {
    val successStatus: Int
    val scalingModeAsPlayed: Int

    fun captureSizeRange(): IntArray

    fun maxCaptureRate(): Int

    fun create(audioSessionId: Int): VisualizerPlatformHandle
}

internal interface VisualizerPlatformHandle {
    fun setCaptureSize(captureSize: Int): Int

    fun setScalingMode(scalingMode: Int): Int

    fun setDataCaptureListener(
        captureRateMilliHz: Int,
        callback: VisualizerFftCallback,
    ): Int

    fun setEnabled(enabled: Boolean): Int

    fun release()
}

internal fun interface VisualizerFftCallback {
    fun onFftData(fft: ByteArray, samplingRateMilliHz: Int)
}

/**
 * Android [Visualizer] 采样实现。所有平台调用均 fail-soft：构造、配置、启用或回调失败
 * 都会释放已创建资源，并让上层保持 null 以启用 baseline 动画。
 */
internal object AndroidVisualizerCaptureFactory : AudioSpectrumCaptureFactory {
    private val delegate = VisualizerCaptureFactory(AndroidVisualizerPlatform)

    override fun create(
        audioSessionId: Int,
        listener: AudioSpectrumCaptureListener,
    ): AudioSpectrumCapture? = delegate.create(audioSessionId, listener)
}

internal class VisualizerCaptureFactory(
    private val platform: VisualizerPlatform,
) : AudioSpectrumCaptureFactory {
    override fun create(
        audioSessionId: Int,
        listener: AudioSpectrumCaptureListener,
    ): AudioSpectrumCapture? {
        if (audioSessionId <= 0) return null

        var capture: PlatformVisualizerCapture? = null
        return try {
            capture = PlatformVisualizerCapture(
                handle = platform.create(audioSessionId),
                successStatus = platform.successStatus,
                listener = listener,
            )
            val captureSize = platform.captureSizeRange().lastOrNull()
                ?: throw IllegalStateException("Visualizer capture size range is empty")

            checkStatus(
                operation = "setCaptureSize",
                status = capture.handle.setCaptureSize(captureSize),
            )
            checkStatus(
                operation = "setScalingMode",
                status = capture.handle.setScalingMode(platform.scalingModeAsPlayed),
            )
            checkStatus(
                operation = "setDataCaptureListener",
                status = capture.handle.setDataCaptureListener(
                    captureRateMilliHz = platform.maxCaptureRate().coerceAtLeast(1),
                    callback = VisualizerFftCallback { fft, samplingRateMilliHz ->
                        try {
                            listener.onFftData(fft, samplingRateMilliHz)
                        } catch (failure: Throwable) {
                            notifyUnavailable(listener, failure)
                        }
                    },
                ),
            )
            checkStatus(
                operation = "setEnabled(true)",
                status = capture.handle.setEnabled(true),
            )
            capture.markEnabled()
            capture
        } catch (failure: Throwable) {
            capture?.release()
            notifyUnavailable(listener, failure)
            null
        }
    }

    private fun checkStatus(operation: String, status: Int) {
        if (status != platform.successStatus) {
            throw VisualizerPlatformFailure(operation, status)
        }
    }
}

private object AndroidVisualizerPlatform : VisualizerPlatform {
    override val successStatus: Int = Visualizer.SUCCESS
    override val scalingModeAsPlayed: Int = Visualizer.SCALING_MODE_AS_PLAYED

    override fun captureSizeRange(): IntArray = Visualizer.getCaptureSizeRange()

    override fun maxCaptureRate(): Int = Visualizer.getMaxCaptureRate()

    override fun create(audioSessionId: Int): VisualizerPlatformHandle =
        AndroidVisualizerPlatformHandle(Visualizer(audioSessionId))
}

private class AndroidVisualizerPlatformHandle(
    private val visualizer: Visualizer,
) : VisualizerPlatformHandle {
    override fun setCaptureSize(captureSize: Int): Int =
        visualizer.setCaptureSize(captureSize)

    override fun setScalingMode(scalingMode: Int): Int =
        visualizer.setScalingMode(scalingMode)

    override fun setDataCaptureListener(
        captureRateMilliHz: Int,
        callback: VisualizerFftCallback,
    ): Int = visualizer.setDataCaptureListener(
        object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(
                visualizer: Visualizer,
                waveform: ByteArray,
                samplingRate: Int,
            ) = Unit

            override fun onFftDataCapture(
                visualizer: Visualizer,
                fft: ByteArray,
                samplingRate: Int,
            ) {
                callback.onFftData(fft, samplingRate)
            }
        },
        captureRateMilliHz,
        /* waveform = */ false,
        /* fft = */ true,
    )

    override fun setEnabled(enabled: Boolean): Int = visualizer.setEnabled(enabled)

    override fun release() {
        visualizer.release()
    }
}

private class PlatformVisualizerCapture(
    internal val handle: VisualizerPlatformHandle,
    private val successStatus: Int,
    private val listener: AudioSpectrumCaptureListener,
) : AudioSpectrumCapture {
    private val released = AtomicBoolean(false)
    private val enabled = AtomicBoolean(false)

    fun markEnabled() {
        enabled.set(true)
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        var failure: Throwable? = null
        if (enabled.compareAndSet(true, false)) {
            try {
                val status = handle.setEnabled(false)
                if (status != successStatus) {
                    failure = VisualizerPlatformFailure("setEnabled(false)", status)
                }
            } catch (caught: Throwable) {
                failure = caught
            }
        }
        try {
            handle.release()
        } catch (caught: Throwable) {
            failure = failure?.also { it.addSuppressed(caught) } ?: caught
        }
        failure?.let { notifyUnavailable(listener, it) }
    }
}

private class VisualizerPlatformFailure(
    operation: String,
    status: Int,
) : IllegalStateException("Visualizer $operation failed with status $status")

private fun notifyUnavailable(listener: AudioSpectrumCaptureListener, failure: Throwable) {
    runCatching { listener.onUnavailable(failure) }
}

/**
 * 一个播放引擎实例的音频采样生命周期。
 *
 * - 每次 bind 先释放旧 session；
 * - clear 可用于 stop 或媒体 session 切换，之后仍可再次 bind；
 * - release 是终态且幂等；
 * - 旧 capture 的迟到回调由 generation 丢弃，不能污染新 session。
 */
internal class AudioSpectrumSessionController(
    private val captureFactory: AudioSpectrumCaptureFactory,
    private val analyzer: FftBandAnalyzer = FftBandAnalyzer(),
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private val _audioBands = MutableStateFlow<AudioBandLevels?>(null)
    val audioBands: StateFlow<AudioBandLevels?> = _audioBands.asStateFlow()

    private var capture: AudioSpectrumCapture? = null
    private var generation = 0L
    private var lastCaptureTimeMs: Long? = null
    private var terminallyReleased = false

    fun bind(audioSessionId: Int) {
        val previous: AudioSpectrumCapture?
        val token: Long
        synchronized(lock) {
            if (terminallyReleased) return
            generation += 1
            token = generation
            previous = capture
            capture = null
            analyzer.reset()
            lastCaptureTimeMs = null
            _audioBands.value = null
        }
        releaseSafely(previous)
        if (audioSessionId <= 0) return

        val listener = object : AudioSpectrumCaptureListener {
            override fun onFftData(fft: ByteArray, samplingRateMilliHz: Int) {
                acceptFft(token, fft, samplingRateMilliHz)
            }

            override fun onUnavailable(cause: Throwable) {
                failSoft(token, cause)
            }
        }

        val created = try {
            captureFactory.create(audioSessionId, listener)
        } catch (failure: Throwable) {
            failSoft(token, failure)
            null
        } ?: return

        val accepted = synchronized(lock) {
            if (!terminallyReleased && generation == token) {
                capture = created
                true
            } else {
                false
            }
        }
        if (!accepted) releaseSafely(created)
    }

    fun clear() {
        val previous = synchronized(lock) {
            generation += 1
            val detached = capture
            capture = null
            analyzer.reset()
            lastCaptureTimeMs = null
            _audioBands.value = null
            detached
        }
        releaseSafely(previous)
    }

    fun release() {
        val previous = synchronized(lock) {
            if (terminallyReleased) return
            terminallyReleased = true
            generation += 1
            val detached = capture
            capture = null
            analyzer.reset()
            lastCaptureTimeMs = null
            _audioBands.value = null
            detached
        }
        releaseSafely(previous)
    }

    private fun acceptFft(
        token: Long,
        fft: ByteArray,
        samplingRateMilliHz: Int,
    ) {
        var failure: Throwable? = null
        synchronized(lock) {
            if (terminallyReleased || generation != token) return
            try {
                val nowMs = monotonicTimeMs()
                val elapsedMs = lastCaptureTimeMs
                    ?.let { previous -> (nowMs - previous).takeIf { it > 0 } }
                    ?: FIRST_CAPTURE_ELAPSED_MS
                lastCaptureTimeMs = nowMs
                _audioBands.value = analyzer.analyze(fft, samplingRateMilliHz, elapsedMs)
            } catch (caught: Throwable) {
                failure = caught
            }
        }
        failure?.let { caught -> failSoft(token, caught) }
    }

    private fun failSoft(token: Long, failure: Throwable) {
        val previous = synchronized(lock) {
            if (terminallyReleased || generation != token) return
            generation += 1
            val detached = capture
            capture = null
            analyzer.reset()
            lastCaptureTimeMs = null
            _audioBands.value = null
            detached
        }
        releaseSafely(previous)
        notifyFailure(failure)
    }

    private fun releaseSafely(toRelease: AudioSpectrumCapture?) {
        if (toRelease == null) return
        runCatching { toRelease.release() }
            .onFailure(::notifyFailure)
    }

    private fun notifyFailure(failure: Throwable) {
        runCatching { onFailure(failure) }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val FIRST_CAPTURE_ELAPSED_MS = 16L
    }
}
