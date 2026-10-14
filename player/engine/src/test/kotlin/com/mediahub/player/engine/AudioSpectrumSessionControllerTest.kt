package com.mediahub.player.engine

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSpectrumSessionControllerTest {

    @Test
    fun `invalid or unsupported session remains explicitly unavailable`() {
        val factory = RecordingCaptureFactory().apply { returnNull = true }
        val controller = AudioSpectrumSessionController(factory)

        controller.bind(0)
        assertEquals(0, factory.createdSessionIds.size)
        assertNull(controller.audioBands.value)

        controller.bind(42)
        assertEquals(listOf(42), factory.createdSessionIds)
        assertNull(controller.audioBands.value)
    }

    @Test
    fun `same session can retry after permission becomes available`() {
        val factory = RecordingCaptureFactory().apply { returnNull = true }
        val controller = AudioSpectrumSessionController(factory)

        controller.bind(42)
        assertNull(controller.audioBands.value)
        assertTrue(factory.captures.isEmpty())

        factory.returnNull = false
        controller.bind(42)
        val capture = factory.captures.single()
        capture.emit(toneFft(100f))

        assertEquals(listOf(42, 42), factory.createdSessionIds)
        assertTrue(requireNotNull(controller.audioBands.value).bass > 0f)
    }

    @Test
    fun `successful capture distinguishes silence from unavailable`() {
        var nowMs = 1_000L
        val factory = RecordingCaptureFactory()
        val controller = AudioSpectrumSessionController(
            captureFactory = factory,
            monotonicTimeMs = { nowMs },
        )

        controller.bind(7)
        val capture = factory.captures.single()
        assertNull(controller.audioBands.value)

        capture.emit(ByteArray(FFT_SIZE))
        assertEquals(AudioBandLevels.ZERO, controller.audioBands.value)

        nowMs += 45L
        capture.emit(toneFft(100f))
        assertNotNull(controller.audioBands.value)
        assertTrue(requireNotNull(controller.audioBands.value).bass > 0f)
    }

    @Test
    fun `factory and runtime failures fail soft to null`() {
        val failures = mutableListOf<Throwable>()
        val createFailure = SecurityException("permission denied")
        val throwingFactory = RecordingCaptureFactory().apply { failureOnCreate = createFailure }
        val throwingController = AudioSpectrumSessionController(
            captureFactory = throwingFactory,
            onFailure = failures::add,
        )

        throwingController.bind(8)
        assertNull(throwingController.audioBands.value)
        assertEquals(listOf(createFailure), failures)

        val runtimeFactory = RecordingCaptureFactory()
        val runtimeController = AudioSpectrumSessionController(
            captureFactory = runtimeFactory,
            onFailure = failures::add,
        )
        runtimeController.bind(9)
        val capture = runtimeFactory.captures.single()
        capture.emit(toneFft(1_000f))
        assertNotNull(runtimeController.audioBands.value)

        val runtimeFailure = IllegalStateException("visualizer stopped")
        capture.fail(runtimeFailure)
        assertNull(runtimeController.audioBands.value)
        assertEquals(1, capture.releaseCount)
        assertTrue(runtimeFailure in failures)

        // 已失效 capture 的迟到数据不能重新点亮旧 session。
        capture.emit(toneFft(1_000f))
        assertNull(runtimeController.audioBands.value)
    }

    @Test
    fun `session rebind releases old capture and rejects stale callbacks`() {
        val factory = RecordingCaptureFactory()
        val controller = AudioSpectrumSessionController(factory)

        controller.bind(11)
        val first = factory.captures.single()
        first.emit(toneFft(100f))
        assertNotNull(controller.audioBands.value)

        controller.bind(12)
        val second = factory.captures.last()
        assertEquals(1, first.releaseCount)
        assertNull(controller.audioBands.value)

        first.emit(toneFft(100f))
        assertNull(controller.audioBands.value)
        second.emit(toneFft(8_000f))
        assertTrue(requireNotNull(controller.audioBands.value).treble > 0f)

        controller.clear()
        assertEquals(1, second.releaseCount)
        assertNull(controller.audioBands.value)
    }

    @Test
    fun `release is idempotent terminal and ignores future bind`() {
        val factory = RecordingCaptureFactory()
        val controller = AudioSpectrumSessionController(factory)

        controller.bind(21)
        val capture = factory.captures.single()
        controller.release()
        controller.release()

        assertEquals(1, capture.releaseCount)
        assertNull(controller.audioBands.value)

        controller.bind(22)
        assertEquals(listOf(21), factory.createdSessionIds)
        capture.emit(toneFft(100f))
        assertNull(controller.audioBands.value)
    }

    private class RecordingCaptureFactory : AudioSpectrumCaptureFactory {
        val createdSessionIds = mutableListOf<Int>()
        val captures = mutableListOf<FakeCapture>()
        var returnNull = false
        var failureOnCreate: Throwable? = null

        override fun create(
            audioSessionId: Int,
            listener: AudioSpectrumCaptureListener,
        ): AudioSpectrumCapture? {
            createdSessionIds += audioSessionId
            failureOnCreate?.let { throw it }
            if (returnNull) return null
            return FakeCapture(listener).also(captures::add)
        }
    }

    private class FakeCapture(
        private val listener: AudioSpectrumCaptureListener,
    ) : AudioSpectrumCapture {
        var releaseCount = 0
            private set

        fun emit(fft: ByteArray) {
            listener.onFftData(fft, SAMPLE_RATE_MILLIHZ)
        }

        fun fail(failure: Throwable) {
            listener.onUnavailable(failure)
        }

        override fun release() {
            releaseCount += 1
        }
    }

    private fun toneFft(frequencyHz: Float): ByteArray {
        val fft = ByteArray(FFT_SIZE)
        val bin = (frequencyHz * FFT_SIZE / SAMPLE_RATE_HZ).roundToInt()
        fft[bin * 2] = Byte.MAX_VALUE
        fft[bin * 2 + 1] = Byte.MAX_VALUE
        return fft
    }

    private companion object {
        const val FFT_SIZE = 1_024
        const val SAMPLE_RATE_HZ = 48_000f
        const val SAMPLE_RATE_MILLIHZ = 48_000_000
    }
}
