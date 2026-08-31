package com.mediahub.player.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizerCaptureFactoryTest {

    @Test
    fun `successful setup uses as played scaling and release is idempotent`() {
        val platform = FakeVisualizerPlatform()
        val listener = RecordingListener()

        val capture = VisualizerCaptureFactory(platform).create(AUDIO_SESSION_ID, listener)

        assertNotNull(capture)
        assertEquals(listOf(AUDIO_SESSION_ID), platform.createdSessionIds)
        assertEquals(
            listOf(
                "setCaptureSize:${platform.captureSizes.last()}",
                "setScalingMode:${platform.scalingModeAsPlayed}",
                "setDataCaptureListener:${platform.captureRate}",
                "setEnabled:true",
            ),
            platform.handle.operations,
        )

        val fft = byteArrayOf(1, 2, 3, 4)
        platform.handle.emit(fft, 48_000_000)
        assertEquals(1, listener.fftEvents.size)
        assertArrayEquals(fft, listener.fftEvents.single().first)
        assertEquals(48_000_000, listener.fftEvents.single().second)

        requireNotNull(capture).release()
        capture.release()

        assertEquals(1, platform.handle.releaseCount)
        assertEquals(1, platform.handle.disableCount)
        assertTrue(listener.failures.isEmpty())
    }

    @Test
    fun `every configuration status failure is unavailable and releases once`() {
        statusFailureCases().forEach { failureCase ->
            val platform = FakeVisualizerPlatform()
            val listener = RecordingListener()
            failureCase.configure(platform.handle)

            val capture = VisualizerCaptureFactory(platform).create(AUDIO_SESSION_ID, listener)

            assertNull(failureCase.operation, capture)
            assertEquals(failureCase.operation, 1, platform.handle.releaseCount)
            assertEquals(failureCase.operation, 1, listener.failures.size)
            assertTrue(
                listener.failures.single().message.orEmpty(),
                listener.failures.single().message.orEmpty().contains(failureCase.operation),
            )
            assertEquals(failureCase.expectedOperations, platform.handle.operations)
        }
    }

    @Test
    fun `disable status failure is reported and native release still runs once`() {
        val platform = FakeVisualizerPlatform()
        val listener = RecordingListener()
        val capture = requireNotNull(
            VisualizerCaptureFactory(platform).create(AUDIO_SESSION_ID, listener),
        )
        platform.handle.disableStatus = ERROR_STATUS

        capture.release()
        capture.release()

        assertEquals(1, platform.handle.disableCount)
        assertEquals(1, platform.handle.releaseCount)
        assertEquals(1, listener.failures.size)
        assertTrue(listener.failures.single().message.orEmpty().contains("setEnabled(false)"))
    }

    @Test
    fun `exception after handle creation is unavailable and releases once`() {
        val platform = FakeVisualizerPlatform().apply {
            handle.throwAtOperation = "setScalingMode"
        }
        val listener = RecordingListener()

        val capture = VisualizerCaptureFactory(platform).create(AUDIO_SESSION_ID, listener)

        assertNull(capture)
        assertEquals(1, platform.handle.releaseCount)
        assertEquals(1, listener.failures.size)
        assertTrue(listener.failures.single().message.orEmpty().contains("setScalingMode"))
    }

    @Test
    fun `empty capture range fails soft and releases constructed handle`() {
        val platform = FakeVisualizerPlatform().apply { captureSizes = intArrayOf() }
        val listener = RecordingListener()

        val capture = VisualizerCaptureFactory(platform).create(AUDIO_SESSION_ID, listener)

        assertNull(capture)
        assertEquals(1, platform.handle.releaseCount)
        assertEquals(1, listener.failures.size)
        assertTrue(listener.failures.single().message.orEmpty().contains("capture size range"))
    }

    @Test
    fun `constructor failure is unavailable without fabricating a capture`() {
        val failure = SecurityException("permission denied")
        val platform = FakeVisualizerPlatform().apply { createFailure = failure }
        val listener = RecordingListener()

        val capture = VisualizerCaptureFactory(platform).create(AUDIO_SESSION_ID, listener)

        assertNull(capture)
        assertEquals(listOf(failure), listener.failures)
        assertEquals(0, platform.handle.releaseCount)
    }

    @Test
    fun `release exception is unavailable and invoked only once`() {
        val platform = FakeVisualizerPlatform()
        val listener = RecordingListener()
        val capture = requireNotNull(
            VisualizerCaptureFactory(platform).create(AUDIO_SESSION_ID, listener),
        )
        platform.handle.releaseFailure = IllegalStateException("release failed")

        capture.release()
        capture.release()

        assertEquals(1, platform.handle.releaseCount)
        assertEquals(1, listener.failures.size)
        assertTrue(listener.failures.single().message.orEmpty().contains("release failed"))
    }

    @Test
    fun `listener exception is converted to unavailable`() {
        val platform = FakeVisualizerPlatform()
        val listener = RecordingListener().apply {
            fftFailure = IllegalArgumentException("bad FFT")
        }
        val capture = requireNotNull(
            VisualizerCaptureFactory(platform).create(AUDIO_SESSION_ID, listener),
        )

        platform.handle.emit(byteArrayOf(1, 2, 3, 4), 48_000_000)

        assertEquals(1, listener.failures.size)
        assertTrue(listener.failures.single().message.orEmpty().contains("bad FFT"))
        capture.release()
    }

    private fun statusFailureCases(): List<StatusFailureCase> = listOf(
        StatusFailureCase(
            operation = "setCaptureSize",
            configure = { it.captureSizeStatus = ERROR_STATUS },
            expectedOperations = listOf("setCaptureSize:1024", "release"),
        ),
        StatusFailureCase(
            operation = "setScalingMode",
            configure = { it.scalingModeStatus = ERROR_STATUS },
            expectedOperations = listOf(
                "setCaptureSize:1024",
                "setScalingMode:73",
                "release",
            ),
        ),
        StatusFailureCase(
            operation = "setDataCaptureListener",
            configure = { it.dataCaptureListenerStatus = ERROR_STATUS },
            expectedOperations = listOf(
                "setCaptureSize:1024",
                "setScalingMode:73",
                "setDataCaptureListener:20000",
                "release",
            ),
        ),
        StatusFailureCase(
            operation = "setEnabled(true)",
            configure = { it.enableStatus = ERROR_STATUS },
            expectedOperations = listOf(
                "setCaptureSize:1024",
                "setScalingMode:73",
                "setDataCaptureListener:20000",
                "setEnabled:true",
                "release",
            ),
        ),
    )

    private data class StatusFailureCase(
        val operation: String,
        val configure: (FakeVisualizerPlatformHandle) -> Unit,
        val expectedOperations: List<String>,
    )

    private class RecordingListener : AudioSpectrumCaptureListener {
        val fftEvents = mutableListOf<Pair<ByteArray, Int>>()
        val failures = mutableListOf<Throwable>()
        var fftFailure: Throwable? = null

        override fun onFftData(fft: ByteArray, samplingRateMilliHz: Int) {
            fftFailure?.let { throw it }
            fftEvents += fft to samplingRateMilliHz
        }

        override fun onUnavailable(cause: Throwable) {
            failures += cause
        }
    }

    private class FakeVisualizerPlatform : VisualizerPlatform {
        override val successStatus: Int = SUCCESS_STATUS
        override val scalingModeAsPlayed: Int = 73
        var captureSizes: IntArray = intArrayOf(256, 1_024)
        var captureRate: Int = 20_000
        var createFailure: Throwable? = null
        val createdSessionIds = mutableListOf<Int>()
        val handle = FakeVisualizerPlatformHandle()

        override fun captureSizeRange(): IntArray = captureSizes

        override fun maxCaptureRate(): Int = captureRate

        override fun create(audioSessionId: Int): VisualizerPlatformHandle {
            createdSessionIds += audioSessionId
            createFailure?.let { throw it }
            return handle
        }
    }

    private class FakeVisualizerPlatformHandle : VisualizerPlatformHandle {
        val operations = mutableListOf<String>()
        var captureSizeStatus = SUCCESS_STATUS
        var scalingModeStatus = SUCCESS_STATUS
        var dataCaptureListenerStatus = SUCCESS_STATUS
        var enableStatus = SUCCESS_STATUS
        var disableStatus = SUCCESS_STATUS
        var throwAtOperation: String? = null
        var releaseFailure: Throwable? = null
        var releaseCount = 0
            private set
        var disableCount = 0
            private set
        private var callback: VisualizerFftCallback? = null

        override fun setCaptureSize(captureSize: Int): Int {
            operations += "setCaptureSize:$captureSize"
            throwIfRequested("setCaptureSize")
            return captureSizeStatus
        }

        override fun setScalingMode(scalingMode: Int): Int {
            operations += "setScalingMode:$scalingMode"
            throwIfRequested("setScalingMode")
            return scalingModeStatus
        }

        override fun setDataCaptureListener(
            captureRateMilliHz: Int,
            callback: VisualizerFftCallback,
        ): Int {
            operations += "setDataCaptureListener:$captureRateMilliHz"
            throwIfRequested("setDataCaptureListener")
            this.callback = callback
            return dataCaptureListenerStatus
        }

        override fun setEnabled(enabled: Boolean): Int {
            operations += "setEnabled:$enabled"
            throwIfRequested("setEnabled($enabled)")
            if (!enabled) disableCount += 1
            return if (enabled) enableStatus else disableStatus
        }

        override fun release() {
            operations += "release"
            releaseCount += 1
            releaseFailure?.let { throw it }
        }

        fun emit(fft: ByteArray, samplingRateMilliHz: Int) {
            requireNotNull(callback).onFftData(fft, samplingRateMilliHz)
        }

        private fun throwIfRequested(operation: String) {
            if (throwAtOperation == operation) {
                throw IllegalStateException("$operation threw")
            }
        }
    }

    private companion object {
        const val AUDIO_SESSION_ID = 42
        const val SUCCESS_STATUS = 0
        const val ERROR_STATUS = -2
    }
}
