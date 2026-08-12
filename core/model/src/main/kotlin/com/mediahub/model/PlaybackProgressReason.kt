package com.mediahub.model

/** 播放进度上报原因；关键事件必须绕过周期节流立即发送。 */
enum class PlaybackProgressReason {
    PERIODIC,
    PLAY,
    PAUSE,
    SEEK,
    STOP,
    END,
}
