package com.mediahub.player.mpv

import android.view.Surface
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.player.engine.PlaybackEvent
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.SeekMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MpvPlaybackEngineTest {
    @Test
    fun `release cancels queued initialization and forbids later play`() = runTest {
        val f = Fixture(backgroundScope)
        f.engine.play(session("old"))
        f.engine.release()
        f.engine.play(session("new"))
        runCurrent()
        assertTrue(f.bridges.isEmpty())
        assertTrue(f.instances.isEmpty())
        assertNull(f.engine.uiState.value.error)
    }

    @Test
    fun `stop cancels queued initialization but permits a new session`() = runTest {
        val f = Fixture(backgroundScope)
        f.engine.play(session("old"))
        assertEquals("old", f.engine.stop()?.itemId)
        runCurrent()
        assertTrue(f.bridges.isEmpty())
        f.engine.play(session("new"))
        runCurrent()
        assertEquals(1, f.instances.size)
        assertEquals("new", f.engine.uiState.value.mediaTitle)
        assertEquals("https://media/new", f.bridges.single().url)
        f.engine.release()
    }

    @Test
    fun `new play replaces a queued session without creating old resources`() = runTest {
        val f = Fixture(backgroundScope)
        f.engine.play(session("old"))
        f.engine.play(session("new"))
        runCurrent()
        assertEquals(1, f.bridges.size)
        assertEquals("https://media/new", f.bridges.single().url)
        assertEquals(1, f.instances.single().loads)
        f.engine.release()
    }

    @Test
    fun `release during bridge startup closes the partial bridge and never creates native`() = runTest {
        val f = Fixture(backgroundScope)
        f.onBridgeStart = { f.engine.release() }
        f.engine.play(session("old"))
        runCurrent()
        assertEquals(1, f.bridges.single().stops)
        assertTrue(f.instances.isEmpty())
        assertNull(f.engine.uiState.value.error)
    }

    @Test
    fun `release during noncooperative native init closes both resources without loadfile`() = runTest {
        val f = Fixture(backgroundScope)
        val events = mutableListOf<PlaybackEvent>()
        val progress = mutableListOf<PlaybackProgress>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.engine.events.collect { events += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.engine.progress.collect { progress += it } }
        f.onInit = { f.engine.release() }
        f.engine.play(session("old"))
        runCurrent()
        val old = f.instances.single()
        assertEquals(0, old.loads)
        assertEquals(1, old.destroys)
        assertEquals(1, f.bridges.single().stops)
        old.observer.property("media-title", "stale")
        old.observer.property("pause", false)
        old.observer.event(MpvInstance.Event.END_FILE)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("old", f.engine.uiState.value.mediaTitle)
        assertFalse(f.engine.uiState.value.isPlaying)
        assertNull(f.engine.uiState.value.error)
        assertTrue(events.isEmpty())
        assertTrue(progress.isEmpty())
        f.engine.release()
        assertEquals(1, old.destroys)
    }

    @Test
    fun `stop during init invalidates before teardown events and returns session snapshot`() = runTest {
        val f = Fixture(backgroundScope)
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.engine.events.collect { events += it } }
        f.onInit = { assertEquals("old", f.engine.stop()?.itemId) }
        f.engine.play(session("old"))
        runCurrent()
        assertEquals(0, f.instances.single().loads)
        assertEquals(1, f.instances.single().destroys)
        assertEquals(listOf(PlaybackEvent.Stopped), events)
        assertNull(f.engine.uiState.value.error)
    }

    @Test
    fun `replacement during native init cleans old resources before creating the new instance`() = runTest {
        val f = Fixture(backgroundScope)
        f.onInit = {
            if (f.instances.size == 1) {
                f.engine.play(session("new"))
                error("old init failed after replacement")
            }
        }
        f.engine.play(session("old"))
        runCurrent()
        assertEquals(2, f.instances.size)
        assertEquals(0, f.instances[0].loads)
        assertEquals(1, f.instances[0].destroys)
        assertEquals(1, f.bridges[0].stops)
        assertEquals(1, f.instances[1].loads)
        assertTrue(f.order.indexOf("destroy-1") < f.order.indexOf("create-2"))
        assertNull(f.engine.uiState.value.error)
        assertEquals("new", f.engine.uiState.value.mediaTitle)
        f.instances[0].observer.property("media-title", "stale")
        f.instances[0].observer.property("time-pos", 900.0)
        assertEquals("new", f.engine.uiState.value.mediaTitle)
        assertEquals(0L, f.engine.uiState.value.positionMs)
        f.engine.release()
    }

    @Test
    fun `failed init reports only current error and cleans both partial resources`() = runTest {
        val f = Fixture(backgroundScope)
        f.onInit = { error("native init failure") }
        f.engine.play(session("old"))
        runCurrent()
        assertEquals(1, f.instances.single().destroys)
        assertEquals(1, f.bridges.single().stops)
        assertEquals(0, f.instances.single().loads)
        assertEquals("native init failure", f.engine.uiState.value.error?.cause?.message)
        f.instances.single().observer.property("pause", false)
        assertFalse(f.engine.uiState.value.isPlaying)
        f.engine.release()
        assertEquals(1, f.instances.single().destroys)
    }

    @Test
    fun `immediate replacement cannot initialize before the previous partial native is destroyed`() = runTest {
        val immediateScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val f = Fixture(immediateScope)
        f.onInit = { if (f.instances.size == 1) f.engine.play(session("new")) }
        f.engine.play(session("old"))
        runCurrent()
        assertEquals(2, f.instances.size)
        assertEquals(0, f.instances[0].loads)
        assertEquals(1, f.instances[1].loads)
        assertTrue(f.order.indexOf("destroy-1") < f.order.indexOf("create-2"))
        assertNull(f.engine.uiState.value.error)
        f.engine.release()
    }

    @Test
    fun `release of active playback stops polling and suppresses stale native callbacks`() = runTest {
        val f = Fixture(backgroundScope)
        val values = mutableListOf<PlaybackProgress>()
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.engine.progress.collect { values += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.engine.events.collect { events += it } }
        f.engine.play(session("old"))
        runCurrent()
        val old = f.instances.single()
        assertEquals(listOf("old"), values.map { it.itemId })
        val reads = old.reads
        f.engine.release()
        old.observer.property("media-title", "stale")
        old.observer.event(MpvInstance.Event.END_FILE)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(reads, old.reads)
        assertEquals(1, values.size)
        assertTrue(events.isEmpty())
        assertEquals("old", f.engine.uiState.value.mediaTitle)
        assertEquals(1, old.destroys)
        assertEquals(1, f.bridges.single().stops)
    }

    @Test
    fun `replacement of active playback isolates progress and destroys old resources once`() = runTest {
        val f = Fixture(backgroundScope)
        val values = mutableListOf<PlaybackProgress>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.engine.progress.collect { values += it } }
        f.engine.play(session("old"))
        runCurrent()
        val old = f.instances.single()
        f.engine.play(session("new"))
        runCurrent()
        val reads = old.reads
        old.observer.property("time-pos", 99.0)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("old", "new", "new"), values.map { it.itemId })
        assertEquals(reads, old.reads)
        assertEquals(1, old.destroys)
        assertEquals(1, f.bridges[0].stops)
        assertEquals(0L, f.engine.uiState.value.positionMs)
        f.engine.release()
        assertEquals(1, old.destroys)
    }

    @Test
    fun `stop captures final native position and releases active resources`() = runTest {
        val f = Fixture(backgroundScope)
        f.engine.play(session("old"))
        runCurrent()
        val native = f.instances.single()
        native.position = 42.5
        val final = f.engine.stop()
        assertEquals("old", final?.itemId)
        assertEquals(42_500L, final?.positionMs)
        assertEquals(1, native.destroys)
        assertEquals(1, f.bridges.single().stops)
        assertNull(f.engine.stop())
        f.engine.release()
        assertEquals(1, native.destroys)
    }

    @Test
    fun `immediate Stopped collector restarts without native and state lock inversion`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val f = Fixture(scope)
        var callbackReturnedDuringDestroy = false
        try {
            scope.launch { f.engine.events.collect { if (it == PlaybackEvent.Stopped) f.engine.play(session("new")) } }
            f.engine.play(session("old"))
            val old = f.instances.single()
            f.onDestroy = {
                val returned = CountDownLatch(1)
                thread {
                    old.observer.event(MpvInstance.Event.END_FILE)
                    returned.countDown()
                }
                callbackReturnedDuringDestroy = returned.await(1, TimeUnit.SECONDS)
            }
            f.engine.stop()
            assertTrue("reentrant startup must be deferred beyond the state monitor", f.deferred.isNotEmpty())
            assertTrue("native destroy's observer must not wait for the state monitor", callbackReturnedDuringDestroy)
            f.flushDeferred()
            assertEquals(2, f.instances.size)
            assertEquals(1, old.destroys)
            assertEquals("new", f.engine.uiState.value.mediaTitle)
        } finally {
            f.engine.release()
            f.flushDeferred()
            scope.cancel()
        }
    }

    @Test
    fun `deferred reentrant control cannot affect a replacement session`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val f = Fixture(scope)
        try {
            f.engine.play(session("old"))
            val old = f.instances.single()
            var handled = false
            scope.launch {
                f.engine.uiState.collect {
                    if (it.speed == 2f && !handled) {
                        handled = true
                        f.engine.seekTo(99_000, SeekMode.COMMIT)
                        f.engine.play(session("new"))
                    }
                }
            }
            old.observer.property("speed", 2.0)
            assertTrue(f.deferred.isNotEmpty())
            f.flushDeferred()
            assertEquals(2, f.instances.size)
            assertEquals(0, old.seeks)
            assertEquals(0, f.instances.last().seeks)
            assertEquals("new", f.engine.uiState.value.mediaTitle)
        } finally {
            f.engine.release()
            f.flushDeferred()
            scope.cancel()
        }
    }

    @Test
    fun `deferred reentrant release cleanup survives cancellation of the playback scope`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val f = Fixture(scope)
        try {
            f.engine.play(session("old"))
            val old = f.instances.single()
            scope.launch {
                f.engine.uiState.collect {
                    if (it.speed == 2f) {
                        scope.cancel()
                        f.engine.release()
                    }
                }
            }
            old.observer.property("speed", 2.0)
            assertTrue(f.deferred.isNotEmpty())
            assertEquals(0, old.destroys)
            f.flushDeferred()
            assertEquals(1, old.destroys)
            assertEquals(1, f.bridges.single().stops)
        } finally {
            f.engine.release()
            f.flushDeferred()
            scope.cancel()
        }
    }

    @Test
    fun `controls and release return while JNI is blocked then destroy its unpublished resources`() {
        val entered = CountDownLatch(1)
        val finishInit = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val bridgeStopped = CountDownLatch(1)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val f = Fixture(scope)
            f.onInit = {
                entered.countDown()
                check(finishInit.await(3, TimeUnit.SECONDS))
            }
            f.onDestroy = { destroyed.countDown() }
            f.onBridgeStop = { bridgeStopped.countDown() }
            try {
                f.engine.play(session("old"))
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                f.engine.attachSurface(null)
                f.engine.seekTo(1_000, SeekMode.COMMIT)
                f.engine.setSpeed(1.5f)
                f.engine.togglePlayPause()
                // Must not wait for finishInit: stop/release only owns published resources.
                f.engine.release()
                assertEquals(1L, destroyed.count)
                finishInit.countDown()
                assertTrue(destroyed.await(3, TimeUnit.SECONDS))
                assertTrue(bridgeStopped.await(3, TimeUnit.SECONDS))
                assertEquals(0, f.instances.single().loads)
                assertEquals(1, f.instances.single().destroys)
                assertNull(f.engine.uiState.value.error)
            } finally {
                finishInit.countDown()
                f.engine.release()
                scope.cancel()
            }
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val bridges = mutableListOf<FakeBridge>()
        val instances = mutableListOf<FakeInstance>()
        val order = mutableListOf<String>()
        val deferred = ArrayDeque<() -> Unit>()
        var onBridgeStart: () -> Unit = {}
        var onBridgeStop: () -> Unit = {}
        var onInit: () -> Unit = {}
        var onDestroy: () -> Unit = {}
        val engine = MpvPlaybackEngine(
            logger = object : Logger {
                override fun d(tag: LogTag, message: String) = Unit
                override fun i(tag: LogTag, message: String) = Unit
                override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
                override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
            },
            scope = scope,
            bridgeFactory = {
                FakeBridge({ onBridgeStart() }, { onBridgeStop() }).also { bridges += it }
            },
            instanceFactory = {
                val id = instances.size + 1
                order += "create-$id"
                FakeInstance({ onInit() }, { order += "destroy-$id"; onDestroy() }).also { instances += it }
            },
            elapsedRealtime = { 100L },
            currentTimeMillis = { 200L },
            deferNative = { deferred.addLast(it) },
        )
        fun flushDeferred() { while (deferred.isNotEmpty()) deferred.removeFirst().invoke() }
    }

    private class FakeBridge(val onStart: () -> Unit, val onStop: () -> Unit) : MpvBridge {
        var url: String? = null
        var stops = 0
        override fun start(url: String, headers: Map<String, String>): String {
            this.url = url
            onStart()
            return "http://127.0.0.1/fake"
        }
        override fun stop() { stops++; onStop() }
    }

    private class FakeInstance(val onInit: () -> Unit, val onDestroy: () -> Unit) : MpvInstance {
        lateinit var observer: MpvInstance.Observer
        var loads = 0
        var destroys = 0
        var reads = 0
        var seeks = 0
        var position = 0.0
        override fun addObserver(observer: MpvInstance.Observer) { this.observer = observer }
        override fun setOptionString(name: String, value: String) = Unit
        override fun init() = onInit()
        override fun attachSurface(surface: Surface) = Unit
        override fun detachSurface() = Unit
        override fun observeProperty(name: String, format: MpvInstance.Format) = Unit
        override fun command(args: Array<String>) {
            if (args.first() == "loadfile") loads++
            if (args.first() == "seek") seeks++
        }
        override fun setPropertyBoolean(name: String, value: Boolean) = Unit
        override fun setPropertyDouble(name: String, value: Double) = Unit
        override fun getPropertyBoolean(name: String): Boolean { reads++; return false }
        override fun getPropertyDouble(name: String): Double { reads++; return if (name == "time-pos") position else 60.0 }
        override fun destroy() {
            destroys++
            if (::observer.isInitialized) observer.event(MpvInstance.Event.END_FILE)
            onDestroy()
        }
    }

    private companion object {
        fun session(id: String) = PlaybackSession(
            serverId = "server", itemId = id, itemTitle = id,
            source = PlaybackSource(url = "https://media/$id", durationMs = 60_000),
        )
    }
}
