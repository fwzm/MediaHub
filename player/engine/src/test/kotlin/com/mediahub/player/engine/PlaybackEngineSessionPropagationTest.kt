package com.mediahub.player.engine

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PlaybackSource.sessionId → PlaybackProgress.sessionId 传播契约（1H repair，
 * ADR-040 correction）：Media3 引擎的周期进度 / currentProgress（含 stop() 的
 * final 路径）都必须携带 source 的会话关联 id；null（Local/WebDAV 等无会话
 * 语义的 Provider）原样传播 null，行为零变化。
 *
 * mpv 引擎同款四构造点逐一注入同一字段（代码路径镜像，JVM 不可实例化 native
 * 引擎，真机 device smoke 以 mpv 为主引擎实弹覆盖）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEngineSessionPropagationTest {

    private fun newEngine(scope: TestScope): PlaybackEngine {
        val context: Context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).build()
        return PlaybackEngine(
            player = player,
            headersHolder = PlaybackHeadersHolder(),
            logger = StdoutLogger(),
            scope = scope,
            speedMonitor = PlaybackSpeedMonitor(),
        )
    }

    private fun source(sessionId: String?) = PlaybackSource(
        url = "https://media.example/stream.mkv",
        mode = PlaybackMode.DIRECT_STREAM,
        sessionId = sessionId,
    )

    private fun session(sessionId: String?) = PlaybackSession(
        serverId = "srv-1",
        itemId = "m1",
        itemTitle = "title",
        source = source(sessionId),
        itemType = MediaType.MOVIE,
    )

    @Test
    fun `source session id propagates to periodic current and final progress`() = runTest {
        val engine = newEngine(this)
        val received = mutableListOf<PlaybackProgress>()
        backgroundScope.launch { engine.progress.collect { received += it } }

        engine.play(session("ps-engine-1"))
        runCurrent() // 首个周期 tick（先 emit 后 delay）
        advanceTimeBy(1_000)
        runCurrent() // 第二个周期 tick

        assertTrue("周期进度必须已产生（实际 ${received.size} 条）", received.size >= 2)
        received.forEach { p ->
            assertEquals("周期进度必须携带 source.sessionId", "ps-engine-1", p.sessionId)
        }
        assertEquals("currentProgress 必须携带 source.sessionId", "ps-engine-1", engine.currentProgress()?.sessionId)
        val final = engine.stop()
        assertEquals("stop() 的 final 进度必须携带 source.sessionId", "ps-engine-1", final?.sessionId)
        engine.release()
    }

    @Test
    fun `null session id propagates as null with zero behavior change`() = runTest {
        val engine = newEngine(this)
        val received = mutableListOf<PlaybackProgress>()
        backgroundScope.launch { engine.progress.collect { received += it } }

        engine.play(session(sessionId = null))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue("null sessionId 不改变进度产生节奏（实际 ${received.size} 条）", received.size >= 2)
        received.forEach { p ->
            assertNull("无会话语义 Provider 的 sessionId 保持 null", p.sessionId)
        }
        assertNull(engine.currentProgress()?.sessionId)
        assertNull(engine.stop()?.sessionId)
        engine.release()
    }
}
