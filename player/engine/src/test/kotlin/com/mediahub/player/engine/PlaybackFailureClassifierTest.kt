package com.mediahub.player.engine

import com.mediahub.core.network.PlaybackError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 缺陷 47 / 87：厂商错误文案归类。 */
class PlaybackFailureClassifierTest {

    @Test
    fun `shared texture failures are classified as surface errors`() {
        assertEquals(
            PlaybackError.Code.SURFACE_ERROR,
            PlaybackFailureClassifier.classify("Failed to create shared texture: -22"),
        )
        assertEquals(
            PlaybackError.Code.SURFACE_ERROR,
            PlaybackFailureClassifier.classify("SurfaceTexture: Unable to dequeue buffer"),
        )
        assertEquals(
            PlaybackError.Code.SURFACE_ERROR,
            PlaybackFailureClassifier.classify("弹出共享纹理失败"),
        )
    }

    @Test
    fun `deep cause chain is inspected`() {
        val root = IllegalStateException("eglCreateContext failed")
        val wrapped = RuntimeException("decoder init", root)
        assertEquals(PlaybackError.Code.SURFACE_ERROR, PlaybackFailureClassifier.classify(wrapped))
    }

    @Test
    fun `data access failures are classified separately`() {
        assertEquals(
            PlaybackError.Code.DATA_ACCESS_ERROR,
            PlaybackFailureClassifier.classify("open failed: EACCES (Permission denied)"),
        )
        assertEquals(
            PlaybackError.Code.DATA_ACCESS_ERROR,
            PlaybackFailureClassifier.classify(java.io.FileNotFoundException("no such file: movie.mkv")),
        )
    }

    @Test
    fun `unrelated failures stay on the original mapping`() {
        assertNull(PlaybackFailureClassifier.classify("Unable to connect to server"))
        assertNull(PlaybackFailureClassifier.classify(null))
    }
}
