package com.mediahub.player.mpv

import com.mediahub.model.ResumeSeekMode

/**
 * 条目 124：mpv 的精确续播参数。
 *
 * mpv 默认 `hr-seek=no`：转发 seek 到下一个关键帧，起播快。
 * 设为 `yes` 后按精确时间定位（更多解码工作，首帧更慢）。
 * 该选项已在 libmpv 1.0.0 二进制的选项表中确认存在（字符串 `hr-seek`）。
 *
 * 必须在 `mpv.init()` 之前下发，否则不会影响随后的 `loadfile` + `start` 定位。
 */
internal object MpvResumeSeekOption {
    const val NAME = "hr-seek"

    fun value(mode: ResumeSeekMode): String = when (mode) {
        ResumeSeekMode.KEYFRAME -> "no"
        ResumeSeekMode.EXACT -> "yes"
    }
}
