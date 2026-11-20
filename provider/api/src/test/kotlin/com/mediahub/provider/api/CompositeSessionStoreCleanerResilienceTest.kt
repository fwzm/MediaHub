package com.mediahub.provider.api

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组合会话清理器的失败韧性（ADR-039 的清理语义补强）。
 *
 * 基线语义：`cleaners.forEach { it.clear(serverId) }` —— 任一 Provider 的清理抛异常，
 * 后续 Provider 的会话元数据**不再被清理**，删除媒体源后留下孤儿 session 记录，
 * 且异常冒泡时**不点名**是哪个 Provider 失败。
 *
 * 目标语义：逐个清理、互不阻断；首个失败保留原始类型与实例原样抛给调用方
 * （删除媒体源的日志行与失败步骤契约不变），失败 Provider 的身份挂在 suppressed 诊断上；
 * 协程取消必须原样透传并立即停止扇出，不得被当成"清理失败"。
 */
class CompositeSessionStoreCleanerResilienceTest {

    private class RecordingCleaner(
        private val name: String,
        private val calls: MutableList<Pair<String, String>>,
        private val failure: Throwable? = null,
    ) : ProviderSessionCleaner {
        override suspend fun clear(serverId: String) {
            calls += name to serverId
            failure?.let { throw it }
        }
    }

    @Test
    fun `a failing provider does not stop the remaining providers`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val composite = CompositeSessionStoreCleaner(
            setOf(
                RecordingCleaner("emby", calls, IOException("session store locked")),
                RecordingCleaner("jellyfin", calls),
                RecordingCleaner("webdav", calls),
            ),
        )

        val thrown = runCatching { composite.clear("srv-9") }.exceptionOrNull()

        assertTrue("失败类型必须保留为原始 IOException，实际=" + thrown, thrown is IOException)
        assertEquals(
            listOf("emby" to "srv-9", "jellyfin" to "srv-9", "webdav" to "srv-9"),
            calls,
        )
    }

    @Test
    fun `the original failure reaches the caller unchanged`() = runBlocking {
        val failure = IOException("session store locked")
        val composite = CompositeSessionStoreCleaner(
            setOf(RecordingCleaner("emby", mutableListOf(), failure)),
        )

        val thrown = runCatching { composite.clear("srv-1") }.exceptionOrNull()

        assertSame("清理失败必须原样抛给调用方，不得被包装替换", failure, thrown)
    }

    @Test
    fun `the failing provider is named in a suppressed diagnostic`() = runBlocking {
        val failure = IOException("session store locked")
        val composite = CompositeSessionStoreCleaner(
            setOf(RecordingCleaner("emby", mutableListOf(), failure)),
        )

        runCatching { composite.clear("srv-2") }

        val diagnostic = failure.suppressed.single()
        val text = diagnostic.message.orEmpty()
        assertTrue("诊断必须点名失败的 Provider 实现，实际=" + text, text.contains("RecordingCleaner"))
        assertTrue("诊断必须带上被清理的 serverId，实际=" + text, text.contains("srv-2"))
    }

    @Test
    fun `only the first failure is reported while every provider still runs`() = runBlocking {
        val first = IOException("emby store locked")
        val second = IllegalStateException("jellyfin store locked")
        val calls = mutableListOf<Pair<String, String>>()
        val composite = CompositeSessionStoreCleaner(
            setOf(
                RecordingCleaner("emby", calls, first),
                RecordingCleaner("jellyfin", calls, second),
                RecordingCleaner("webdav", calls),
            ),
        )

        val thrown = runCatching { composite.clear("srv-3") }.exceptionOrNull()

        assertSame("多个 Provider 同时失败时必须报第一个失败", first, thrown)
        assertEquals("后续 Provider 仍必须被清理", 3, calls.size)
    }

    @Test
    fun `a healthy composite clears every provider exactly once`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        CompositeSessionStoreCleaner(
            setOf(
                RecordingCleaner("emby", calls),
                RecordingCleaner("jellyfin", calls),
                RecordingCleaner("webdav", calls),
            ),
        ).clear("srv-4")

        assertEquals(3, calls.size)
        assertEquals(setOf("emby", "jellyfin", "webdav"), calls.map { it.first }.toSet())
        assertTrue("每个 Provider 都必须收到同一个 serverId", calls.all { it.second == "srv-4" })
    }

    @Test
    fun `cancellation propagates unchanged and stops the fan out`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val cancellation = CancellationException("scope cancelled")
        val composite = CompositeSessionStoreCleaner(
            setOf(
                RecordingCleaner("emby", calls, cancellation),
                RecordingCleaner("jellyfin", calls),
            ),
        )

        val thrown = runCatching { composite.clear("srv-5") }.exceptionOrNull()

        assertSame("取消必须原样透传，不得被当成清理失败", cancellation, thrown)
        assertEquals("scope cancelled", thrown?.message)
        assertEquals("取消不得被附加清理诊断", 0, cancellation.suppressed.size)
        assertEquals("取消后不得继续扇出", listOf("emby" to "srv-5"), calls)
    }
}
