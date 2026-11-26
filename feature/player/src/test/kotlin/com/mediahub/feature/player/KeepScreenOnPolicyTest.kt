package com.mediahub.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 条目 104：播放中保屏、暂停恢复系统行为的判定矩阵。 */
class KeepScreenOnPolicyTest {

    @Test
    fun `playing keeps the screen on`() {
        assertTrue(KeepScreenOnPolicy.shouldKeepScreenOn(ResolveState.Ready, playWhenReady = true))
    }

    @Test
    fun `buffering with play intent still keeps the screen on`() {
        // 缓冲时 isPlaying 可能为 false，但 playWhenReady 仍代表用户要播放。
        assertTrue(KeepScreenOnPolicy.shouldKeepScreenOn(ResolveState.Ready, playWhenReady = true))
    }

    @Test
    fun `paused playback restores the system screen timeout`() {
        assertFalse(KeepScreenOnPolicy.shouldKeepScreenOn(ResolveState.Ready, playWhenReady = false))
    }

    @Test
    fun `resolving a new source does not hold the screen`() {
        assertFalse(KeepScreenOnPolicy.shouldKeepScreenOn(ResolveState.Resolving, playWhenReady = true))
        assertFalse(
            KeepScreenOnPolicy.shouldKeepScreenOn(
                ResolveState.Failed("boom"),
                playWhenReady = true,
            )
        )
    }
}
