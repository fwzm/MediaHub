package com.mediahub.core.common

/** 时间源抽象，便于测试注入。 */
fun interface TimeProvider {
    fun nowEpochMs(): Long
}

object SystemTimeProvider : TimeProvider {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}
