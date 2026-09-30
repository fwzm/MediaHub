package com.mediahub.core.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2-4 第 3 项：Logger 实现写入缓冲的 throwable 文本必须脱敏。
 *
 * - LogBuffer/MemoryLogger 是纯 JVM 路径，直接端到端验证（诊断导出内容）。
 * - LogcatLogger 走 android.util.Log 无法在纯 JVM 断言，其 throwable 文本
 *   由同一条脱敏管道产出（safeLogText），此处通过 MemoryLogger 覆盖同一缺陷类。
 */
class LogcatLoggerSanitizationTest {

    private val secret = "Sup3rS3cret"

    @Test
    fun `memory logger w redacts throwable message in buffer`() {
        val buffer = LogBuffer()
        val logger = MemoryLogger(buffer)

        logger.w(LogTag.PROVIDER, "加载媒体库失败", RuntimeException("host unreachable token=$secret"))

        val line = buffer.snapshot().single()
        assertFalse("缓冲不得含原始异常文本：$line", line.contains(secret))
    }

    @Test
    fun `memory logger e redacts own message and never includes nested cause text`() {
        val buffer = LogBuffer()
        val logger = MemoryLogger(buffer)

        logger.e(
            LogTag.NETWORK,
            "请求失败",
            IllegalStateException("outer password=Sup3rS3cret", RuntimeException("inner api_key=N3st3dS3cret")),
        )

        val line = buffer.snapshot().single()
        // 自身 message 经 Redactor：key=value 形态的值脱敏
        assertFalse("缓冲不得含自身 message 明文：$line", line.contains("Sup3rS3cret"))
        // 嵌套 cause 的 message 根本不进缓冲（LogBuffer 只拼自身 message）
        assertFalse("缓冲不得含嵌套 cause 文本：$line", line.contains("N3st3dS3cret"))
    }

    @Test
    fun `memory logger keeps exception class name for diagnosis`() {
        val buffer = LogBuffer()
        val logger = MemoryLogger(buffer)

        logger.w(LogTag.UI, "操作失败", RuntimeException("token=$secret"))

        val line = buffer.snapshot().single()
        assertTrue("类名保留供诊断：$line", line.contains("RuntimeException"))
        assertFalse(line.contains(secret))
    }

    @Test
    fun `memory logger without throwable is unchanged`() {
        val buffer = LogBuffer()
        val logger = MemoryLogger(buffer)

        logger.w(LogTag.UI, "普通告警")

        assertTrue(buffer.snapshot().single().contains("普通告警"))
    }
}
