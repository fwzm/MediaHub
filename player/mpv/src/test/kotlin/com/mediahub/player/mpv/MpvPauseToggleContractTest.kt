package com.mediahub.player.mpv

import android.view.Surface
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.model.PlaybackSource
import com.mediahub.player.engine.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G3 暂停/恢复契约测试（确定性，不依赖真机时序）。
 *
 * 真机现象（EXECUTED_FAIL，已多轮复现）：点应用内暂停后播放位置继续推进。
 *
 * 代码层假设：MpvPlaybackEngine.togglePlayPause() 用 _uiState.value.isPlaying 决定写入值，
 * 而 isPlaying 的唯一更新源是 mpv 的 pause 属性观察回调。若该回调在起播后没有把
 * isPlaying 置为 true，它会停在默认 false，于是首次点击写入 pause=false 等于继续播放。
 *
 * 本测试固定契约：起播后首次点击暂停，必须向 mpv 写入 pause=true。
 */
class MpvPauseToggleContractTest {

    private class RecordingInstance : MpvInstance {
        lateinit var observer: MpvInstance.Observer
        val booleanWrites = mutableListOf<Pair<String, Boolean>>()
        @Volatile var loads = 0
        override fun addObserver(observer: MpvInstance.Observer) { this.observer = observer }
        override fun setOptionString(name: String, value: String) = Unit
        override fun init() = Unit
        override fun attachSurface(surface: Surface) = Unit
        override fun detachSurface() = Unit
        override fun observeProperty(name: String, format: MpvInstance.Format) = Unit
        override fun command(args: Array<String>) { if (args.firstOrNull() == "loadfile") loads++ }
        override fun setPropertyBoolean(name: String, value: Boolean) {
            synchronized(booleanWrites) { booleanWrites += name to value }
        }
        override fun setPropertyDouble(name: String, value: Double) = Unit
        override fun getPropertyBoolean(name: String): Boolean? = false
        override fun getPropertyDouble(name: String): Double? = if (name == "time-pos") 1.0 else 60.0
        override fun getPropertyString(name: String): String? = null
        override fun destroy() = Unit
    }

    private class FakeBridge : MpvBridge {
        override fun start(url: String, headers: Map<String, String>): String = "http://127.0.0.1:1/media/tok"
        override fun startLocalFile(path: String): String = "http://127.0.0.1:1/media/tok"
        override fun stop() = Unit
    }

    private fun engine(instance: RecordingInstance): MpvPlaybackEngine {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return MpvPlaybackEngine(
            logger = StdoutLogger(),
            scope = scope,
            bridgeFactory = { FakeBridge() },
            instanceFactory = { instance },
            elapsedRealtime = { System.nanoTime() / 1_000_000L },
            currentTimeMillis = { System.currentTimeMillis() },
            deferNative = { action -> action() },
        )
    }

    private fun session(id: String) = PlaybackSession(
        serverId = "server",
        itemId = id,
        itemTitle = id,
        source = PlaybackSource(url = "https://media/" + id, durationMs = 60_000),
    )

    private fun awaitLoad(instance: RecordingInstance) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (instance.loads == 0 && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue("起播必须真实发出 loadfile", instance.loads > 0)
    }

    @Test
    fun `toggling pause on a freshly started session actually requests pause`() = runBlocking {
        val instance = RecordingInstance()
        val eng = engine(instance)
        try {
            eng.play(session("item-1"))
            awaitLoad(instance)

            synchronized(instance.booleanWrites) { instance.booleanWrites.clear() }
            eng.togglePlayPause()

            val pauseWrite = synchronized(instance.booleanWrites) {
                instance.booleanWrites.lastOrNull { it.first == "pause" }
            }
            assertNotNull("点击暂停必须向 mpv 写入 pause 属性", pauseWrite)
            val all = synchronized(instance.booleanWrites) { instance.booleanWrites.toList() }
            assertTrue("首次点击暂停必须写入 pause=true（写 false 等于继续播放）。实际：" + all, pauseWrite!!.second)
        } finally {
            runCatching { eng.release() }
        }
    }
}