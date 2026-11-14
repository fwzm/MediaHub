package com.mediahub.player.engine

import androidx.media3.exoplayer.SeekParameters
import com.mediahub.model.ResumeSeekMode

/**
 * 条目 124：把用户偏好映射为 Media3 ExoPlayer 的 seek 参数。
 *
 * - [ResumeSeekMode.KEYFRAME] → [SeekParameters.CLOSEST_SYNC]：落在目标位置附近的关键帧，
 *   起播快，但实际位置与记录值可能有偏差（Media3 默认行为）。
 * - [ResumeSeekMode.EXACT] → [SeekParameters.EXACT]：解码到精确位置，首帧更慢。
 *
 * ExoPlayer 的 seek 参数是播放器级设置，作用于之后触发的 seek；因此每次 `play()`
 * 都按当前会话重新下发，避免跨会话残留上一次的精度设置。
 */
internal object ResumeSeekParameters {
    fun forMode(mode: ResumeSeekMode): SeekParameters = when (mode) {
        ResumeSeekMode.KEYFRAME -> SeekParameters.CLOSEST_SYNC
        ResumeSeekMode.EXACT -> SeekParameters.EXACT
    }
}
