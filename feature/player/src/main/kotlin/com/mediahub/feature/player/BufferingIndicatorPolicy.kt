package com.mediahub.feature.player

/**
 * 缓冲指示策略（条目 19）。
 *
 * 命中断点缓存区间的 seek 会短暂进入 buffering，但网络速度为 0；
 * 此时不应显示「0KB/s + 转圈」的网络加载态。规则：
 * - 正在读本地缓存 → 不显示；
 * - 缓冲时间未超过 [GRACE_MS]（缓存/快速 seek 的瞬时缓冲）→ 不显示；
 * - 其余持续缓冲 → 显示。
 */
object BufferingIndicatorPolicy {
    const val GRACE_MS = 400L

    fun shouldShow(
        isBuffering: Boolean,
        isReadingFromCache: Boolean,
        bufferingAgeMs: Long,
    ): Boolean = isBuffering && !isReadingFromCache && bufferingAgeMs >= GRACE_MS
}
