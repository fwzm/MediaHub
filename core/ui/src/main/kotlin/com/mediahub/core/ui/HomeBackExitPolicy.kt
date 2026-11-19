package com.mediahub.core.ui

/**
 * 首页返回键双击退出策略（纯状态机，便于 JVM 单测）。
 * 第一次返回只提示；同一窗口内第二次返回才退出。
 */
object HomeBackExitPolicy {

    const val EXIT_WINDOW_MS: Long = 2_000L

    sealed interface Decision {
        data object ShowHint : Decision
        data object Exit : Decision
    }

    fun decide(nowMs: Long, lastBackAtMs: Long?): Decision {
        if (lastBackAtMs == null) return Decision.ShowHint
        val elapsedMs = nowMs - lastBackAtMs
        return if (elapsedMs in 0..EXIT_WINDOW_MS) Decision.Exit else Decision.ShowHint
    }
}
