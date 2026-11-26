package com.mediahub.feature.player

/**
 * 条目 104：播放器保屏策略。
 *
 * 只有播放源已解析成功、且用户播放意图为「播放」时，才持有
 * `FLAG_KEEP_SCREEN_ON`。暂停、解析中、解析失败都必须恢复系统锁屏行为；
 * 缓冲中仍保持常亮（用户仍在等待播放，不应熄屏）。
 *
 * 抽成纯函数是为了能在 JVM 单测里覆盖判定矩阵；实际的窗口标志接线在
 * [PlayerRoute] 中完成。
 */
internal object KeepScreenOnPolicy {
    fun shouldKeepScreenOn(resolve: ResolveState, playWhenReady: Boolean): Boolean =
        resolve is ResolveState.Ready && playWhenReady
}
