package com.mediahub.player.mpv

import android.view.Surface
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.PlaybackSource
import com.mediahub.player.engine.PlaybackEvent
import com.mediahub.player.engine.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * G3 · mpv 暂停切换的**极性来源**契约。
 *
 * 真实缺陷：旧实现取 `_uiState.value.isPlaying` —— isPlaying 是异步 observer 回调
 * 写入的派生状态，在起播瞬间（pause 属性首次回传前）恒为 PlaybackUiState 的默认值
 * false。于是「第一次点击暂停」会下发 `pause=false`（即继续播放），引擎状态毫无变化，
 * 用户看到的就是「点了暂停，位置还在走」。
 *
 * 契约：极性必须取自 **mpv 的权威属性 pause**，不依赖回调时序。
 * 本测试故意从不回调 observer（模拟回调尚未到达），只让 getPropertyBoolean 返回
 * 序列，从而把「极性是否依赖异步状态」钉死。
 */
class MpvTogglePolarityTest {

    private class FakeBridge : MpvBridge {
        override fun start(url: String, headers: Map<String, String>) = "http://127.0.0.1/mock"
        override fun startLocalFile(path: String) = "http://127.0.0.1/mock-local"
        override fun stop() = Unit
    }

    private class FakeInstance : MpvInstance {
        var observer: MpvInstance.Observer? = null
        val pauseWrites = mutableListOf<Boolean>()
        /** mpv 的权威 pause 属性：测试直接控制它，模拟"引擎当时真实的暂停态"。 */
        var currentPause = false
        override fun addObserver(observer: MpvInstance.Observer) { this.observer = observer }
        override fun setOptionString(name: String, value: String) = Unit
        override fun init() = Unit
        override fun attachSurface(surface: Surface) = Unit
        override fun detachSurface() = Unit
        override fun observeProperty(name: String, format: MpvInstance.Format) = Unit
        override fun command(args: Array<String>) = Unit
        override fun setPropertyBoolean(name: String, value: Boolean) {
            if (name == "pause") { pauseWrites += value; currentPause = value }
        }
        override fun setPropertyDouble(name: String, value: Double) = Unit
        override fun getPropertyBoolean(name: String): Boolean? =
            if (name == "pause") currentPause else null
        override fun getPropertyDouble(name: String): Double? = null
        override fun getPropertyString(name: String): String? = null
        override fun destroy() = Unit
    }

    private fun session() = PlaybackSession(
        serverId = "srv-g3",
        itemId = "mpv-g3",
        itemTitle = "mpv fixture",
        source = PlaybackSource(url = "https://media.example/g3.mkv", mimeType = "video/x-matroska"),
    )

    @Test
    fun `toggle polarity comes from native pause property not from async isPlaying`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val instance = FakeInstance()
        val engine = MpvPlaybackEngine(
            logger = NoLogger,
            scope = scope,
            bridgeFactory = { FakeBridge() },
            instanceFactory = { instance },
            elapsedRealtime = { 100L },
            currentTimeMillis = { 200L },
            deferNative = { it() },
        )
        try {
            engine.play(session())
            withTimeout(5_000) { while (instance.observer == null) delay(10) }
            // 关键：从不回调 observer → isPlaying 保持默认 false（旧实现会因此判成「继续播放」）
            assertFalse("测试前提：UI 状态未被 observer 更新", engine.uiState.value.isPlaying)

            val events = mutableListOf<PlaybackEvent>()
            val collector = scope.launch { engine.events.collect { events += it.kind } }

            // 阶段 1：引擎正在播放（pause=false）→ 点击必须是「暂停」
            instance.currentPause = false
            engine.togglePlayPause()
            assertEquals(
                "在播时点击必须下发 pause=true（暂停），实际写入 " + instance.pauseWrites,
                listOf(true),
                instance.pauseWrites,
            )

            // 阶段 2：引擎已暂停（pause=true）→ 点击必须是「继续」
            instance.currentPause = true
            engine.togglePlayPause()
            assertEquals(
                "暂停后点击必须下发 pause=false（继续），实际写入 " + instance.pauseWrites,
                listOf(true, false),
                instance.pauseWrites,
            )
            withTimeout(2_000) { while (events.size < 2) delay(10) }
            assertEquals(listOf(PlaybackEvent.Paused, PlaybackEvent.Resumed), events)
            collector.cancel()
        } finally {
            engine.release()
            scope.cancel()
        }
    }

    private object NoLogger : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
}
