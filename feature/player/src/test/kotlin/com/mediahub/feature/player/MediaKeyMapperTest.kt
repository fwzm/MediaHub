package com.mediahub.feature.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V18-B：媒体键映射契约（纯函数，无设备依赖）。
 *
 * 真实缺口：应用无 MediaSession / 无按键分发，KEYCODE_MEDIA_PAUSE 无人接收。
 * 本测试固定"哪些键归我们处理、哪些必须放行、重复键是否幂等"。
 */
class MediaKeyMapperTest {

    @Test
    fun `play pause and headset hook map to toggle`() {
        assertEquals(MediaKeyAction.TOGGLE, MediaKeyMapper.actionFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(MediaKeyAction.TOGGLE, MediaKeyMapper.actionFor(KeyEvent.KEYCODE_HEADSETHOOK))
    }

    @Test
    fun `explicit play and pause map to their own actions`() {
        assertEquals(MediaKeyAction.PLAY, MediaKeyMapper.actionFor(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(MediaKeyAction.PAUSE, MediaKeyMapper.actionFor(KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    @Test
    fun `other keys are not consumed so system behaviour is preserved`() {
        assertNull("音量键不得被播放器消费", MediaKeyMapper.actionFor(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull("音量键不得被播放器消费", MediaKeyMapper.actionFor(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull("返回键交给 BackHandler", MediaKeyMapper.actionFor(KeyEvent.KEYCODE_BACK))
        assertNull(MediaKeyMapper.actionFor(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertNull(MediaKeyMapper.actionFor(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }

    @Test
    fun `explicit keys are idempotent while toggle always acts`() {
        assertFalse("已在播放时再按 PLAY 不应重复动作", MediaKeyMapper.shouldAct(MediaKeyAction.PLAY, playWhenReady = true))
        assertTrue("暂停中按 PLAY 应开始播放", MediaKeyMapper.shouldAct(MediaKeyAction.PLAY, playWhenReady = false))
        assertTrue("播放中按 PAUSE 应暂停", MediaKeyMapper.shouldAct(MediaKeyAction.PAUSE, playWhenReady = true))
        assertFalse("已暂停时再按 PAUSE 不应重复动作", MediaKeyMapper.shouldAct(MediaKeyAction.PAUSE, playWhenReady = false))
        assertTrue("TOGGLE 始终切换", MediaKeyMapper.shouldAct(MediaKeyAction.TOGGLE, playWhenReady = true))
        assertTrue("TOGGLE 始终切换", MediaKeyMapper.shouldAct(MediaKeyAction.TOGGLE, playWhenReady = false))
    }
}
