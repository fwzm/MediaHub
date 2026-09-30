package com.mediahub.provider.api

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3-4 异常契约回归（修订 A2 的"IOException 原实例透传"裁决）：
 *
 * - F-C1-1 契约保持：`ProviderException.Network.cause is IOException`（类型）。
 * - 消毒升级：IOException cause 是**类型保持的消毒视图**（不再是原实例），
 *   秘密不得出现在顶层 message、cause 链、suppressed、标准渲染；
 * - 取消异常仍原实例透传（不可吞红线）；
 * - 非 IO cause 维持 SanitizedProviderCause。
 *
 * 泄漏面矩阵每项以合成秘密 `Sup3rS3cret` 断言。
 */
class ProviderExceptionIoPassthroughTest {

    private val marker = "Sup3rS3cret"

    private fun hostileIo(): IOException {
        val e = IOException("connect failed http://u:$marker@host/x token=$marker")
        e.addSuppressed(IllegalStateException("suppressed pw=$marker"))
        e.initCause(IllegalStateException("nested cause=$marker"))
        return e
    }

    private fun assertNoMarker(text: String?, where: String) {
        assertFalse("$where 泄漏合成秘密: $text", text?.contains(marker) == true)
    }

    @Test
    fun `io cause stays an IOException while all text surfaces are sanitized`() {
        val e = ProviderException.Network("s1", hostileIo())

        assertTrue("F-C1-1 类型契约", e.cause is IOException)
        assertEquals("A2-4 message 契约", "网络错误，请检查网络连接", e.message)

        val cause = e.cause!!
        assertNoMarker(cause.message, "cause.message")
        assertNoMarker(cause.cause?.message, "嵌套 cause.message")
        cause.suppressedExceptions.forEach { sup -> assertNoMarker(sup.message, "suppressed") }
        assertNoMarker(cause.stackTraceToString(), "cause.stackTraceToString")
        assertNoMarker(cause.toString(), "cause.toString")
    }

    @Test
    fun `standard rendering of the provider exception is free of secrets`() {
        val e = ProviderException.Network("s1", hostileIo())

        val buffer = ByteArrayOutputStream()
        e.printStackTrace(PrintStream(buffer))
        val rendered = buffer.toString()
        assertNoMarker(rendered, "printStackTrace")
        assertNoMarker(e.stackTraceToString(), "provider stackTraceToString")
        assertNoMarker(e.toString(), "provider toString")
        // 诊断线索保留：原异常类名仍在（不吞掉超时/DNS 信息）
        assertTrue(
            "消毒视图应保留原异常类名便于诊断",
            e.cause!!.message!!.contains("IOException"),
        )
    }

    @Test
    fun `cancellation cause passes through unsanitized`() {
        val cancel = kotlin.coroutines.cancellation.CancellationException("scope cancelled")
        val e = ProviderException.Parse("s1", cancel)
        assertSame("取消必须原实例透传", cancel, e.cause)
    }

    @Test
    fun `non io causes remain sanitized`() {
        val hostile = IllegalStateException("boom token=$marker")
        val e = ProviderException.Unknown("s1", hostile)

        assertFalse(e.cause!!.message!!.contains(marker))
        assertEquals("sanitized: ${hostile.javaClass.name}", e.cause!!.message)
        assertEquals("未知错误", e.message)
        assertNoMarker(e.cause!!.stackTraceToString().let { e.toString() + it }, "non-io 渲染")
    }
}
