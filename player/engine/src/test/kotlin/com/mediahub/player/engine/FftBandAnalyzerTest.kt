package com.mediahub.player.engine

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FftBandAnalyzerTest {

    @Test
    fun `tones map to bass mid and treble bands`() {
        val analyzer = FftBandAnalyzer()

        val bass = analyzer.rawLevels(toneFft(100f), SAMPLE_RATE_MILLIHZ)
        val mid = analyzer.rawLevels(toneFft(1_000f), SAMPLE_RATE_MILLIHZ)
        val treble = analyzer.rawLevels(toneFft(8_000f), SAMPLE_RATE_MILLIHZ)

        assertTrue(bass.bass > 0f)
        assertEquals(0f, bass.mid, 0f)
        assertEquals(0f, bass.treble, 0f)
        assertTrue(bass.amplitude in 0f..bass.bass)

        assertTrue(mid.mid > 0f)
        assertEquals(0f, mid.bass, 0f)
        assertEquals(0f, mid.treble, 0f)
        assertTrue(mid.amplitude in 0f..mid.mid)

        assertTrue(treble.treble > 0f)
        assertEquals(0f, treble.bass, 0f)
        assertEquals(0f, treble.mid, 0f)
        assertTrue(treble.amplitude in 0f..treble.treble)
    }

    @Test
    fun `quiet and loud input preserve monotonic energy`() {
        val analyzer = FftBandAnalyzer()

        val quiet = analyzer.rawLevels(toneFft(100f, magnitude = 8), SAMPLE_RATE_MILLIHZ)
        val loud = analyzer.rawLevels(toneFft(100f, magnitude = 120), SAMPLE_RATE_MILLIHZ)

        assertTrue(quiet.bass > 0f)
        assertTrue(loud.bass > quiet.bass)
        assertTrue(quiet.amplitude > 0f)
        assertTrue(loud.amplitude > quiet.amplitude)
    }

    @Test
    fun `mixed input exposes energy in every populated band`() {
        val analyzer = FftBandAnalyzer()
        val mixed = mixedFft(
            frequencyAndMagnitude = listOf(100f to 100, 1_000f to 70),
        )

        val levels = analyzer.rawLevels(mixed, SAMPLE_RATE_MILLIHZ)

        assertTrue(levels.bass > 0f)
        assertTrue(levels.mid > 0f)
        assertEquals(0f, levels.treble, 0f)
        assertTrue(levels.amplitude > 0f)
    }

    @Test
    fun `broadband power is not biased toward wider bands`() {
        val analyzer = FftBandAnalyzer()

        val levels = analyzer.rawLevels(broadbandFft(FFT_SIZE, magnitude = 48), SAMPLE_RATE_MILLIHZ)

        assertTrue(levels.bass > 0f)
        assertEquals(levels.bass, levels.mid, FLOAT_TOLERANCE)
        assertEquals(levels.bass, levels.treble, FLOAT_TOLERANCE)
        assertEquals(levels.bass, levels.amplitude, FLOAT_TOLERANCE)
    }

    @Test
    fun `sample rate and capture size preserve band classification`() {
        val analyzer = FftBandAnalyzer()
        val configurations = listOf(
            32_000 to 512,
            44_100 to 1_024,
            48_000 to 2_048,
            96_000 to 1_024,
        )

        configurations.forEach { (sampleRateHz, fftSize) ->
            val samplingRateMilliHz = sampleRateHz * 1_000
            val bass = analyzer.rawLevels(
                toneFft(100f, sampleRateHz.toFloat(), fftSize),
                samplingRateMilliHz,
            )
            val mid = analyzer.rawLevels(
                toneFft(1_000f, sampleRateHz.toFloat(), fftSize),
                samplingRateMilliHz,
            )
            val treble = analyzer.rawLevels(
                toneFft(8_000f, sampleRateHz.toFloat(), fftSize),
                samplingRateMilliHz,
            )

            val label = "sampleRate=$sampleRateHz fftSize=$fftSize"
            assertTrue("bass misclassified at $label", bass.bass > 0f && bass.mid == 0f && bass.treble == 0f)
            assertTrue("mid misclassified at $label", mid.mid > 0f && mid.bass == 0f && mid.treble == 0f)
            assertTrue(
                "treble misclassified at $label",
                treble.treble > 0f && treble.bass == 0f && treble.mid == 0f,
            )
        }
    }

    @Test
    fun `broadband normalization is stable across capture sizes`() {
        val analyzer = FftBandAnalyzer()
        val levels = listOf(512, 1_024, 2_048).map { fftSize ->
            analyzer.rawLevels(
                broadbandFft(fftSize, magnitude = 32),
                SAMPLE_RATE_MILLIHZ,
            )
        }

        levels.drop(1).forEach { candidate ->
            assertEquals(levels.first().bass, candidate.bass, FLOAT_TOLERANCE)
            assertEquals(levels.first().mid, candidate.mid, FLOAT_TOLERANCE)
            assertEquals(levels.first().treble, candidate.treble, FLOAT_TOLERANCE)
            assertEquals(levels.first().amplitude, candidate.amplitude, FLOAT_TOLERANCE)
        }
    }

    @Test
    fun `successful silent FFT is non exceptional zero`() {
        val analyzer = FftBandAnalyzer()

        assertEquals(
            AudioBandLevels.ZERO,
            analyzer.analyze(ByteArray(FFT_SIZE), SAMPLE_RATE_MILLIHZ, 16L),
        )
    }

    @Test
    fun `invalid input is normalized to finite zero`() {
        val analyzer = FftBandAnalyzer()

        assertEquals(AudioBandLevels.ZERO, analyzer.rawLevels(byteArrayOf(1, 2), SAMPLE_RATE_MILLIHZ))
        assertEquals(AudioBandLevels.ZERO, analyzer.rawLevels(ByteArray(FFT_SIZE), 0))
        assertEquals(AudioBandLevels.ZERO, analyzer.rawLevels(ByteArray(FFT_SIZE), -1))
    }

    @Test
    fun `EMA attacks faster than it releases`() {
        val analyzer = FftBandAnalyzer(attackMs = 45f, releaseMs = 250f)

        val attacked = analyzer.analyze(toneFft(100f), SAMPLE_RATE_MILLIHZ, 45L)
        val released = analyzer.analyze(ByteArray(FFT_SIZE), SAMPLE_RATE_MILLIHZ, 45L)

        assertTrue(attacked.bass > 0f)
        assertTrue(released.bass in 0f..<attacked.bass)
        assertTrue(released.bass > attacked.bass * 0.7f)
        assertTrue(attacked.amplitude > 0f)
        assertTrue(released.amplitude in 0f..<attacked.amplitude)
    }

    @Test
    fun `all outputs remain finite and clamped`() {
        val analyzer = FftBandAnalyzer(attackMs = 0f, releaseMs = 0f)
        val saturated = ByteArray(FFT_SIZE) { Byte.MIN_VALUE }

        val levels = analyzer.analyze(saturated, SAMPLE_RATE_MILLIHZ, Long.MAX_VALUE)

        listOf(levels.bass, levels.mid, levels.treble, levels.amplitude).forEach { value ->
            assertTrue(value.isFinite())
            assertTrue(value in 0f..1f)
        }
    }

    private fun toneFft(
        frequencyHz: Float,
        sampleRateHz: Float = SAMPLE_RATE_HZ,
        fftSize: Int = FFT_SIZE,
        magnitude: Int = Byte.MAX_VALUE.toInt(),
    ): ByteArray = mixedFft(
        frequencyAndMagnitude = listOf(frequencyHz to magnitude),
        sampleRateHz = sampleRateHz,
        fftSize = fftSize,
    )

    private fun mixedFft(
        frequencyAndMagnitude: List<Pair<Float, Int>>,
        sampleRateHz: Float = SAMPLE_RATE_HZ,
        fftSize: Int = FFT_SIZE,
    ): ByteArray {
        val fft = ByteArray(fftSize)
        frequencyAndMagnitude.forEach { (frequencyHz, magnitude) ->
            require(magnitude in 0..Byte.MAX_VALUE)
            val bin = (frequencyHz * fftSize / sampleRateHz).roundToInt()
            require(bin in 1 until fftSize / 2)
            fft[bin * 2] = magnitude.toByte()
            fft[bin * 2 + 1] = magnitude.toByte()
        }
        return fft
    }

    private fun broadbandFft(fftSize: Int, magnitude: Int): ByteArray {
        require(magnitude in 0..Byte.MAX_VALUE)
        return ByteArray(fftSize).also { fft ->
            for (bin in 1 until fftSize / 2) {
                fft[bin * 2] = magnitude.toByte()
                fft[bin * 2 + 1] = magnitude.toByte()
            }
        }
    }

    private companion object {
        const val FFT_SIZE = 1_024
        const val SAMPLE_RATE_HZ = 48_000f
        const val SAMPLE_RATE_MILLIHZ = 48_000_000
        const val FLOAT_TOLERANCE = 0.000_001f
    }
}
