package com.mediahub.player.engine

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PlaybackStartupTrace 纯逻辑测试（U4-A）。
 * 覆盖：milestone 只记第一次、summary 计算、traceId 隔离、失败摘要、无伪造数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackStartupTraceTest {

    private fun newTrace(itemId: String = "795218") = PlaybackStartupTrace(
        traceId = PlaybackStartupTrace.newTraceId(),
        serverId = "srv-1",
        itemId = itemId,
        requestedEngineMode = "AUTO",
    )

    @Test
    fun `milestone 只记录第一次不被覆盖`() {
        val t = newTrace()
        t.record(PlaybackStartupTrace.Milestone.PLAY_REQUESTED)
        val first = t.milestoneElapsedMs(PlaybackStartupTrace.Milestone.PLAY_REQUESTED)!!
        Thread.sleep(5)
        t.record(PlaybackStartupTrace.Milestone.PLAY_REQUESTED)
        assertEquals(first, t.milestoneElapsedMs(PlaybackStartupTrace.Milestone.PLAY_REQUESTED))
    }

    @Test
    fun `未记录的 milestone 返回 null 不伪造`() {
        val t = newTrace()
        assertNull(t.milestoneElapsedMs(PlaybackStartupTrace.Milestone.FIRST_FRAME_RENDERED))
    }

    @Test
    fun `summary 包含 playbackInfo duration`() {
        val t = newTrace()
        t.record(PlaybackStartupTrace.Milestone.PLAY_REQUESTED)
        t.record(PlaybackStartupTrace.Milestone.DETAIL_SNAPSHOT_READY)
        t.record(PlaybackStartupTrace.Milestone.PLAYBACK_INFO_REQUEST_STARTED)
        t.record(PlaybackStartupTrace.Milestone.PLAYBACK_INFO_RESPONSE_RECEIVED)
        val summary = t.summary()
        assertTrue(summary.contains("playbackInfo="))
        assertTrue(summary.contains("itemId=795218"))
        assertTrue(summary.contains("mode=AUTO"))
    }

    @Test
    fun `totalTTFF 只在 FIRST_FRAME_RENDERED 后出现`() {
        val t = newTrace()
        t.record(PlaybackStartupTrace.Milestone.PLAY_REQUESTED)
        assertTrue(!t.summary().contains("totalTTFF"))
        t.record(PlaybackStartupTrace.Milestone.FIRST_FRAME_RENDERED)
        assertTrue(t.summary().contains("totalTTFF="))
    }

    @Test
    fun `失败摘要标记 failedStage 和 errorCode`() {
        val t = newTrace()
        t.record(PlaybackStartupTrace.Milestone.PLAY_REQUESTED)
        t.record(PlaybackStartupTrace.Milestone.FAILED)
        t.putMetadata("failedStage", "MEDIA_FIRST_BYTE")
        t.putMetadata("errorCode", "NETWORK_TIMEOUT")
        val summary = t.summary()
        assertTrue(summary.contains("failedStage=MEDIA_FIRST_BYTE"))
        assertTrue(summary.contains("errorCode=NETWORK_TIMEOUT"))
        assertTrue(!summary.contains("totalTTFF"))
    }

    @Test
    fun `两次播放 trace 隔离不串数据`() {
        val t1 = newTrace("item-A")
        val t2 = newTrace("item-B")
        assertNotEquals(t1.traceId, t2.traceId)
        assertEquals("item-A", t1.itemId)
        assertEquals("item-B", t2.itemId)
    }

    @Test
    fun `metadata 引擎和签名正确存储`() {
        val t = newTrace()
        t.putMetadata("engine", "MPV")
        t.putMetadata("signature", "mkv|h264|dts")
        assertEquals("MPV", t.metadata("engine"))
        assertEquals("mkv|h264|dts", t.metadata("signature"))
    }

    @Test
    fun `summary 不含 token 或 Authorization`() {
        val t = newTrace()
        t.record(PlaybackStartupTrace.Milestone.PLAY_REQUESTED)
        t.record(PlaybackStartupTrace.Milestone.FIRST_FRAME_RENDERED)
        val summary = t.summary()
        assertTrue(!summary.contains("token", ignoreCase = true))
        assertTrue(!summary.contains("Authorization", ignoreCase = true))
        assertTrue(!summary.contains("Cookie", ignoreCase = true))
    }

    private fun assertNotEquals(a: String, b: String) {
        assertTrue("expected not equal: $a == $b", a != b)
    }
}