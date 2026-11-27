package com.mediahub.app.navigation

/**
 * 首页「再按一次返回退出」的窗口状态机（纯逻辑，可在 JVM 单测中注入时钟）。
 *
 * 首次返回只记录时间点并返回 false（调用方提示用户再按一次）；
 * 窗口内的第二次返回返回 true（调用方 finish 当前 Activity）。
 */
class DoubleBackExitController(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var armedAtMs: Long? = null

    /** @return true 表示应立即退出。 */
    fun onBack(): Boolean {
        val now = clock()
        val armed = armedAtMs
        if (armed != null) {
            val elapsed = now - armed
            if (elapsed in 0..windowMs) {
                armedAtMs = null
                return true
            }
        }
        armedAtMs = now
        return false
    }

    fun reset() {
        armedAtMs = null
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2_000L
    }
}
