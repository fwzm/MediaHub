package com.mediahub.core.logging

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志汇聚器的单点故障隔离（日志链红线）：
 * 一个 sink 抛异常不得让其余 sink 丢失日志，也不得把异常抛给调用方。
 * 取消异常例外——它必须原样透传（与 provider 层取消透传同一红线）。
 */
class LoggerSinkSafetyTest {

    private class RecordingLogger : Logger {
        val messages = mutableListOf<String>()
        var lastThrowable: Throwable? = null

        override fun d(tag: LogTag, message: String) { messages += "D:" + message }
        override fun i(tag: LogTag, message: String) { messages += "I:" + message }
        override fun w(tag: LogTag, message: String, throwable: Throwable?) {
            messages += "W:" + message
            lastThrowable = throwable
        }

        override fun e(tag: LogTag, message: String, throwable: Throwable?) {
            messages += "E:" + message
            lastThrowable = throwable
        }
    }

    private class ThrowingLogger(private val error: Throwable = IllegalStateException("sink down")) : Logger {
        override fun d(tag: LogTag, message: String): Unit = throw error
        override fun i(tag: LogTag, message: String): Unit = throw error
        override fun w(tag: LogTag, message: String, throwable: Throwable?): Unit = throw error
        override fun e(tag: LogTag, message: String, throwable: Throwable?): Unit = throw error
    }

    @Test
    fun a_failing_sink_does_not_swallow_the_message_for_other_sinks() {
        val healthy = RecordingLogger()
        CompositeLogger(listOf(ThrowingLogger(), healthy)).i(LogTag.UI, "播放开始")
        assertEquals(listOf("I:播放开始"), healthy.messages)
    }

    @Test
    fun a_failing_sink_in_the_middle_does_not_drop_the_later_sink() {
        val first = RecordingLogger()
        val last = RecordingLogger()
        CompositeLogger(listOf(first, ThrowingLogger(), last)).e(LogTag.NETWORK, "请求失败")
        assertEquals(listOf("E:请求失败"), first.messages)
        assertEquals(listOf("E:请求失败"), last.messages)
    }

    @Test
    fun sink_failures_never_reach_the_caller_on_any_level() {
        val composite = CompositeLogger(listOf(ThrowingLogger()))
        composite.d(LogTag.UI, "d")
        composite.i(LogTag.UI, "i")
        composite.w(LogTag.UI, "w")
        composite.e(LogTag.UI, "e")
    }

    @Test
    fun a_healthy_sink_still_receives_the_original_throwable() {
        val healthy = RecordingLogger()
        val boom = IllegalArgumentException("boom")
        CompositeLogger(listOf(ThrowingLogger(), healthy)).w(LogTag.PROVIDER, "降级", boom)
        assertSame(boom, healthy.lastThrowable)
    }

    @Test
    fun cancellation_from_a_sink_is_not_converted_into_silent_success() {
        val composite = CompositeLogger(listOf(ThrowingLogger(CancellationException("cancel"))))
        var thrown: Throwable? = null
        try {
            composite.i(LogTag.UI, "x")
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("取消必须原样透传，不得被当作已吞掉的 sink 故障", thrown is CancellationException)
    }

    @Test
    fun healthy_only_composite_forwards_in_declaration_order() {
        val first = RecordingLogger()
        val second = RecordingLogger()
        CompositeLogger(listOf(first, second)).i(LogTag.UI, "hello")
        assertEquals(listOf("I:hello"), first.messages)
        assertEquals(listOf("I:hello"), second.messages)
    }
}
