package com.mediahub.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断缓冲的字节预算（A2-4 诊断导出：缓冲区必须对内存有上界）。
 *
 * 现有实现只按条数淘汰（capacity），单条长度完全不受限：一条超长异常堆栈
 * （Redactor.MAX_OUTPUT_LENGTH 允许单条 20000 字符）即可占满整个缓冲，
 * 或者多条中等长度日志叠加后突破预期内存上界。
 */
class LogBufferBudgetTest {

    private val suffix = "…[truncated]"

    @Test
    fun `oversized entry is clipped so the buffer stays within its char budget`() {
        val buffer = LogBuffer(capacity = 10, maxChars = 200)

        repeat(10) { buffer.append("x".repeat(100)) }

        val snapshot = buffer.snapshot()
        assertTrue("缓冲总字符数必须受预算约束，实际 ${totalChars(snapshot)}", totalChars(snapshot) <= 200)
        assertTrue("最近一条必须保留（尾部），实际 ${snapshot.last().length}", snapshot.isNotEmpty())
    }

    @Test
    fun `clipping a very long entry keeps its head and marks the truncation`() {
        val buffer = LogBuffer(capacity = 2, maxChars = 100)

        buffer.append("HEAD-MARKER-" + "A".repeat(480) + "-TAIL-MARKER")

        val line = buffer.snapshot().single()
        assertTrue("截断后长度必须 <= 预算，实际 ${line.length}", line.length <= 100)
        assertTrue("必须保留开头（诊断需要看到异常首行）：$line", line.startsWith("HEAD-MARKER-"))
        assertFalse("被截掉的尾部不得留下：$line", line.contains("TAIL-MARKER"))
        assertTrue("必须显式标记截断，实际 $line", line.endsWith(suffix))
    }

    @Test
    fun `entries within budget are kept byte for byte`() {
        val buffer = LogBuffer(capacity = 3, maxChars = 10_000)

        buffer.append("first")
        buffer.append("second")
        buffer.append("third")

        assertEquals(listOf("first", "second", "third"), buffer.snapshot())
    }

    @Test
    fun `eviction prefers the oldest lines until the budget is met`() {
        val buffer = LogBuffer(capacity = 5, maxChars = 25)

        repeat(5) { index -> buffer.append("line-${index}aaaa") }

        val snapshot = buffer.snapshot()
        assertEquals(listOf("line-3aaaa", "line-4aaaa"), snapshot)
    }

    @Test
    fun `capacity still bounds the entry count when budget is large`() {
        val buffer = LogBuffer(capacity = 2, maxChars = 100_000)

        repeat(5) { index -> buffer.append("entry-$index") }

        assertEquals(listOf("entry-3", "entry-4"), buffer.snapshot())
    }

    private fun totalChars(lines: List<String>): Int = lines.sumOf { it.length }
}
