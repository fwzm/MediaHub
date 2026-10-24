package com.mediahub.feature.player

import android.view.KeyEvent

/**
 * V18-B：媒体键 → 播放动作映射（纯函数，可确定性测试）。
 *
 * 背景：全仓无 MediaSession / 无 dispatchKeyEvent / 无 onKeyEvent，
 * 因此 adb KEYCODE_MEDIA_PAUSE 或耳机/蓝牙媒体键都**没有接收者**。
 *
 * 产品契约（本轮最小范围，仅前台播放器）：
 * - MEDIA_PLAY_PAUSE / HEADSETHOOK → TOGGLE（与 UI 暂停按钮同一语义）
 * - MEDIA_PLAY / MEDIA_PAUSE → 显式语义，幂等（已是该状态则不动作）
 * - 其它按键不消费（返回 null），避免抢占音量/返回等系统行为
 *
 * 后台/锁屏控制需要 MediaSession（未包含在本轮范围）。
 */
enum class MediaKeyAction { TOGGLE, PLAY, PAUSE }

object MediaKeyMapper {

    /** @return 需要执行的动作；null 表示不处理该键（继续冒泡给系统）。 */
    fun actionFor(keyCode: Int): MediaKeyAction? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        -> MediaKeyAction.TOGGLE

        KeyEvent.KEYCODE_MEDIA_PLAY -> MediaKeyAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaKeyAction.PAUSE
        else -> null
    }

    /**
     * 结合当前播放意图判定是否需要真的切换。
     *
     * @param playWhenReady 当前播放意图（true=应在播放）
     * @return true 表示需要执行切换/置位；false 表示状态已满足，无副作用
     */
    fun shouldAct(action: MediaKeyAction, playWhenReady: Boolean): Boolean = when (action) {
        MediaKeyAction.TOGGLE -> true
        MediaKeyAction.PLAY -> !playWhenReady
        MediaKeyAction.PAUSE -> playWhenReady
    }
}
