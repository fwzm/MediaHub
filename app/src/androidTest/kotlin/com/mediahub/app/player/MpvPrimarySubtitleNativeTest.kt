package com.mediahub.app.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual pinned JNI properties, with local synthetic media. No server/rendering claim. */
@RunWith(AndroidJUnit4::class)
class MpvPrimarySubtitleNativeTest {
    @Test(timeout = 20_000)
    fun pinnedNativeConfirmsPrimaryFullFilenameAndOff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "a4-native-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        val media = File(directory, "silence.wav")
        val subtitle = File(directory, "fixture.srt")
        val dataSize = 8000 * 2 * 2
        media.writeBytes(ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
        }.array())
        subtitle.writeText("1\n00:00:00,000 --> 00:00:02,000\nSynthetic native capability fixture\n")
        var instance: MPVLib? = null
        try {
            val native = checkNotNull(MPVLib.create(context))
            instance = native
            val loaded = CountDownLatch(1)
            native.addObserver(object : MPVLib.EventObserver {
                override fun eventProperty(property: String) = Unit
                override fun eventProperty(property: String, value: Long) = Unit
                override fun eventProperty(property: String, value: Boolean) = Unit
                override fun eventProperty(property: String, value: Double) = Unit
                override fun eventProperty(property: String, value: String) = Unit
                override fun event(eventId: Int) {
                    if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) loaded.countDown()
                }
            })
            listOf("vo" to "null", "ao" to "null", "keep-open" to "yes", "terminal" to "no").forEach {
                assertTrue("native option ${it.first}", native.setOptionString(it.first, it.second) >= 0)
            }
            native.init()
            native.command(arrayOf("loadfile", media.absolutePath))
            assertTrue("actual native FILE_LOADED", loaded.await(5, TimeUnit.SECONDS))
            native.command(arrayOf("sub-add", subtitle.absolutePath, "select"))
            var targetId: String? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (targetId == null && System.nanoTime() < deadline) {
                val count = native.getPropertyDouble("track-list/count")?.toInt() ?: 0
                for (index in 0 until count) {
                    val prefix = "track-list/$index"
                    val id = native.getPropertyString("$prefix/id")
                    if (native.getPropertyString("$prefix/type") == "sub" &&
                        native.getPropertyString("$prefix/external-filename") == subtitle.absolutePath &&
                        native.getPropertyBoolean("$prefix/selected") == true &&
                        id != null && native.getPropertyString("sid") == id
                    ) targetId = id
                }
                if (targetId == null) CountDownLatch(1).await(10, TimeUnit.MILLISECONDS)
            }
            assertNotNull("actual filename/id/selected/sid must agree", targetId)
            native.command(arrayOf("set", "sid", "no"))
            val offDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (native.getPropertyString("sid") != "no" && System.nanoTime() < offDeadline) {
                CountDownLatch(1).await(10, TimeUnit.MILLISECONDS)
            }
            assertEquals("no", native.getPropertyString("sid"))
        } finally {
            try { instance?.destroy() } finally { directory.deleteRecursively() }
        }
    }
}
