package com.mediahub.player.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 归一化音频能量，所有分量必须是有限的 `0..1`。
 *
 * 非 null 的 [ZERO] 表示采样后端正常但当前静音；端口上的 null 表示没有可用采样后端。
 */
data class AudioBandLevels(
    val bass: Float,
    val mid: Float,
    val treble: Float,
    val amplitude: Float,
) {
    init {
        require(bass.isFinite() && bass >= 0f && bass <= 1f) { "bass must be finite and normalized" }
        require(mid.isFinite() && mid >= 0f && mid <= 1f) { "mid must be finite and normalized" }
        require(treble.isFinite() && treble >= 0f && treble <= 1f) { "treble must be finite and normalized" }
        require(amplitude.isFinite() && amplitude >= 0f && amplitude <= 1f) {
            "amplitude must be finite and normalized"
        }
    }

    companion object {
        val ZERO = AudioBandLevels(0f, 0f, 0f, 0f)
    }
}

/**
 * Android Visualizer FFT 数据的纯 Kotlin 频段分析器。
 *
 * Visualizer 的采样率单位是 milliHertz；FFT 数据按实部/虚部 byte 对排列。每个频段
 * 累计复数 bin power，并按该频段的有效 bin 数计算 RMS，因此宽频段不会仅因 bin 更多
 * 而天然偏亮；overall amplitude 同样使用全部有效 bin 的 RMS。RMS 再经过固定 byte
 * 满量程归一化与对数压缩，最后使用非对称 EMA（快 attack、慢 release）抑制抖动。
 */
class FftBandAnalyzer(
    private val attackMs: Float = DEFAULT_ATTACK_MS,
    private val releaseMs: Float = DEFAULT_RELEASE_MS,
) {
    init {
        require(attackMs >= 0f && attackMs.isFinite())
        require(releaseMs >= 0f && releaseMs.isFinite())
    }

    private var smoothed = AudioBandLevels.ZERO

    @Synchronized
    fun analyze(
        fft: ByteArray,
        samplingRateMilliHz: Int,
        elapsedMs: Long,
    ): AudioBandLevels {
        val target = rawLevels(fft, samplingRateMilliHz)
        val deltaMs = elapsedMs.coerceIn(MIN_ELAPSED_MS, MAX_ELAPSED_MS).toFloat()
        smoothed = AudioBandLevels(
            bass = smooth(smoothed.bass, target.bass, deltaMs),
            mid = smooth(smoothed.mid, target.mid, deltaMs),
            treble = smooth(smoothed.treble, target.treble, deltaMs),
            amplitude = smooth(smoothed.amplitude, target.amplitude, deltaMs),
        )
        return smoothed
    }

    @Synchronized
    fun reset() {
        smoothed = AudioBandLevels.ZERO
    }

    internal fun rawLevels(
        fft: ByteArray,
        samplingRateMilliHz: Int,
    ): AudioBandLevels {
        if (fft.size < MIN_FFT_SIZE || samplingRateMilliHz <= 0) return AudioBandLevels.ZERO

        val sampleRateHz = samplingRateMilliHz / MILLIHERTZ_PER_HERTZ
        if (!sampleRateHz.isFinite() || sampleRateHz <= 0f) return AudioBandLevels.ZERO

        var bassPower = 0.0
        var midPower = 0.0
        var treblePower = 0.0
        var totalPower = 0.0
        var bassBins = 0
        var midBins = 0
        var trebleBins = 0
        var totalBins = 0
        val lastComplexBinExclusive = fft.size / 2

        for (bin in 1 until lastComplexBinExclusive) {
            val realIndex = bin * 2
            val imaginaryIndex = realIndex + 1
            if (imaginaryIndex >= fft.size) break

            val frequencyHz = bin * sampleRateHz / fft.size
            if (frequencyHz < MIN_ANALYZED_HZ || frequencyHz >= MAX_ANALYZED_HZ) continue

            val real = fft[realIndex].toDouble()
            val imaginary = fft[imaginaryIndex].toDouble()
            val power = real * real + imaginary * imaginary
            totalPower += power
            totalBins += 1

            when {
                frequencyHz < BASS_MAX_HZ -> {
                    bassPower += power
                    bassBins += 1
                }
                frequencyHz < MID_MAX_HZ -> {
                    midPower += power
                    midBins += 1
                }
                else -> {
                    treblePower += power
                    trebleBins += 1
                }
            }
        }

        return AudioBandLevels(
            bass = normalizedRms(bassPower, bassBins),
            mid = normalizedRms(midPower, midBins),
            treble = normalizedRms(treblePower, trebleBins),
            amplitude = normalizedRms(totalPower, totalBins),
        )
    }

    private fun normalizedRms(power: Double, binCount: Int): Float {
        if (binCount <= 0 || !power.isFinite() || power <= 0.0) return 0f
        val rms = sqrt(power / binCount)
        val normalized = (rms / MAX_BYTE_COMPLEX_MAGNITUDE).toFloat().coerceIn(0f, 1f)
        return logCompress(normalized)
    }

    private fun smooth(previous: Float, target: Float, elapsedMs: Float): Float {
        val timeConstant = if (target > previous) attackMs else releaseMs
        if (timeConstant == 0f) return target.sanitize()
        val alpha = (1f - exp((-elapsedMs / timeConstant).toDouble()).toFloat()).coerceIn(0f, 1f)
        return (previous + (target - previous) * alpha).sanitize()
    }

    private fun logCompress(value: Float): Float =
        (ln(1f + LOG_GAIN * value) / ln(1f + LOG_GAIN)).sanitize()

    private fun Float.sanitize(): Float =
        if (isFinite()) coerceIn(0f, 1f) else 0f

    private companion object {
        const val DEFAULT_ATTACK_MS = 45f
        const val DEFAULT_RELEASE_MS = 250f
        const val MIN_ELAPSED_MS = 1L
        const val MAX_ELAPSED_MS = 1_000L
        const val MIN_FFT_SIZE = 4
        const val MILLIHERTZ_PER_HERTZ = 1_000f
        const val MIN_ANALYZED_HZ = 20f
        const val BASS_MAX_HZ = 250f
        const val MID_MAX_HZ = 2_000f
        const val MAX_ANALYZED_HZ = 16_000f
        const val LOG_GAIN = 9f
        val MAX_BYTE_COMPLEX_MAGNITUDE = sqrt(2.0 * 128.0 * 128.0)
    }
}
