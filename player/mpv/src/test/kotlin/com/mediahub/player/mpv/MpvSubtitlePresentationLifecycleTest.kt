package com.mediahub.player.mpv

import android.view.Surface
import com.mediahub.core.logging.*
import com.mediahub.model.PlaybackSource
import com.mediahub.model.SubtitleStyle
import com.mediahub.player.engine.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MpvSubtitlePresentationLifecycleTest {
    private class Native : MpvInstance {
        val options = mutableMapOf<String, String>()
        val live = mutableMapOf<String, String>()
        var initOptions = emptyMap<String, String>()
        var onInit: () -> Unit = {}
        var onSet: () -> Unit = {}
        var writes = 0
        override fun addObserver(observer: MpvInstance.Observer) = Unit
        override fun setOptionString(name: String, value: String) { options[name] = value; writes++ }
        override fun init() { initOptions = options.toMap(); onInit() }
        override fun command(args: Array<String>) {
            if (args.first() == "set") { live[args[1]] = args[2]; writes++; onSet() }
        }
        override fun attachSurface(surface: Surface) = Unit
        override fun detachSurface() = Unit
        override fun observeProperty(name: String, format: MpvInstance.Format) = Unit
        override fun setPropertyBoolean(name: String, value: Boolean) = Unit
        override fun setPropertyDouble(name: String, value: Double) = Unit
        override fun getPropertyBoolean(name: String): Boolean? = false
        override fun getPropertyDouble(name: String): Double? = 15.0
        override fun getPropertyString(name: String): String? = null
        override fun destroy() = Unit
    }
    private class Fixture(scope: CoroutineScope) {
        val natives = mutableListOf<Native>()
        var onInit: () -> Unit = {}
        val deferred = ArrayDeque<() -> Unit>()
        val engine = MpvPlaybackEngine(object : Logger {
            override fun d(tag: LogTag, message: String) = Unit
            override fun i(tag: LogTag, message: String) = Unit
            override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
            override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
        }, scope, {
            object : MpvBridge {
                override fun start(url: String, headers: Map<String, String>) = "http://127.0.0.1/fixture"
                override fun stop() = Unit
            }
        }, { Native().also { it.onInit = { onInit() }; natives += it } }, { 1L }, { 1L },
            deferNative = { deferred += it })
        fun close() { engine.release(); while (deferred.isNotEmpty()) deferred.removeFirst()() }
    }
    private fun session(id: String) = PlaybackSession("fixture", id, id,
        PlaybackSource("https://fixture.invalid/$id", durationMs = 60_000))

    @Test fun presentationIsAppliedBeforeInitLiveAndBeforeReentryPlay() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.engine.setSubtitlePresentation(SubtitleStyle(textScale = 1.5f), 24)
            f.engine.play(session("first")); runCurrent()
            val first = f.natives.single()
            assertEquals("76.0", first.initOptions["sub-font-size"])
            assertEquals("no", first.initOptions["sub-ass-override"])
            f.engine.setSubtitlePresentation(SubtitleStyle(applyEmbeddedStyles = false), 36)
            assertEquals("76.0", first.live["sub-font-size"])
            assertEquals("force", first.live["sub-ass-override"])
            f.engine.stop()
            f.engine.play(session("second")); runCurrent()
            assertEquals("force", f.natives.last().initOptions["sub-ass-override"])
            assertEquals("76.0", f.natives.last().initOptions["sub-font-size"])
            f.close(); val count = f.natives.sumOf { it.writes }
            f.engine.setSubtitlePresentation(SubtitleStyle(), 18)
            assertEquals(count, f.natives.sumOf { it.writes })
        } finally { f.close() }
    }

    @Test fun preferencesChangedDuringUnpublishedInitReachThePublishedNative() = runTest {
        val f = Fixture(backgroundScope)
        f.onInit = { f.engine.setSubtitlePresentation(SubtitleStyle(textColor = 0xFF00FF00.toInt()), 36) }
        try {
            f.engine.play(session("init-update")); runCurrent()
            val native = f.natives.single()
            assertEquals("0.0/1.0/0.0/1.0", native.live["sub-color"])
            assertEquals("76.0", native.live["sub-font-size"])
        } finally { f.close() }
    }

    @Test fun releaseDuringALiveStyleWritePreventsRemainingNativeWrites() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.engine.play(session("release-update")); runCurrent()
            val native = f.natives.single()
            native.live.clear()
            native.onSet = { f.engine.release() }
            f.engine.setSubtitlePresentation(SubtitleStyle(textScale = 2f), 24)
            assertEquals("The release callback must actually be reached", 1, native.live.size)
        } finally { f.close() }
    }
}
