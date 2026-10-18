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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEEK-B 发现的真实缺陷：**到达 EOF 后 UI 仍认为「正在播放」**。
 *
 * 真机证据（`123e243f`，APK `3fb7c1b`）：把进度拖到片尾后，
 * - UI 文本 `3:04 / 3:05`（184/185s）；
 * - 按钮 content-desc 仍是**「暂停」**（= playWhenReady 仍 true 且 isEnded 为 false）；
 * - 间隔 2s 的两张截图 **sha256 完全相同**（画面冻结）；
 * - 用户**无法**用播放按钮恢复（没有「播放/重播」可点）。
 *
 * 根因：mpv 的 `END_FILE` 事件只发 `PlaybackEvent.Ended`，
 * **从不更新 `_uiState.isEnded`**；而进度循环里的 `eof-reached` 在 mpv 结束后不一定可读，
 * 于是 UI 的 `showPauseIcon = playWhenReady && !isEnded` 判据（G3 已加）永远不生效。
 *
 * 契约：收到 `END_FILE` 时必须
 *  1) `isEnded = true`；
 *  2) `playWhenReady = false`（不再自称在播放）；
 *  3) 仍发出一次 `PlaybackEvent.Ended`（进度同步用）。
 */
class MpvEndedStateContractTest {

    private class FakeBridge : MpvBridge {
        override fun start(url: String, headers: Map<String, String>) = "http://127.0.0.1/mock"
        override fun startLocalFile(path: String) = "http://127.0.0.1/mock-local"
        override fun stop() = Unit
    }

    private class FakeInstance : MpvInstance {
        var observer: MpvInstance.Observer? = null
        override fun addObserver(observer: MpvInstance.Observer) { this.observer = observer }
        override fun setOptionString(name: String, value: String) = Unit
        override fun init() = Unit
        override fun attachSurface(surface: Surface) = Unit
        override fun detachSurface() = Unit
        override fun observeProperty(name: String, format: MpvInstance.Format) = Unit
        override fun command(args: Array<String>) = Unit
        override fun setPropertyBoolean(name: String, value: Boolean) = Unit
        override fun setPropertyDouble(name: String, value: Double) = Unit
        override fun getPropertyBoolean(name: String): Boolean? =
            // 关键：EOF 后 mpv 的 eof-reached 在真实设备上不一定仍可读；这里模拟「读不到」。
            when (name) {
                "pause" -> false
                "eof-reached" -> null
                else -> null
            }
        override fun getPropertyDouble(name: String): Double? = null
        override fun getPropertyString(name: String): String? = null
        override fun destroy() = Unit
    }

    private fun session() = PlaybackSession(
        serverId = "srv-seekb",
        itemId = "eof-item",
        itemTitle = "eof fixture",
        source = PlaybackSource(url = "https://media.example/eof.mp4", mimeType = "video/mp4"),
    )

    @Test
    fun `END_FILE marks the session ended and no longer playing`() = runBlocking {
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

            // 起播后：未结束（playWhenReady 由 observer 回调驱动，这里只断言 isEnded）
            assertFalse("起播后 isEnded 应为 false", engine.uiState.value.isEnded)

            val events = mutableListOf<PlaybackEvent>()
            val collector = scope.launch { engine.events.collect { events += it } }

            // mpv 播放结束
            instance.observer!!.event(MpvInstance.Event.END_FILE)
            withTimeout(2_000) { while (events.isEmpty()) delay(10) }

            val state = engine.uiState.value
            assertTrue("EOF 后 isEnded 必须为 true（否则 UI 仍显示「暂停」）", state.isEnded)
            assertFalse("EOF 后 playWhenReady 必须为 false（不再自称在播放）", state.playWhenReady)
            assertEquals("仍必须发出一次 Ended 事件（进度同步用）", listOf(PlaybackEvent.Ended), events)
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
