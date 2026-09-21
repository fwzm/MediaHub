package com.mediahub.provider.api

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联合候选跨分支契约回归（A2-4 × F-C1-1 冲突消解）：
 *
 * - F-C1-1（PR #18，Agent C 分层回归）：身份守卫失败必须以
 *   `ProviderException.Network` + **cause 为 IOException 本体** 呈现
 *   （OkHttp/Media3 的失败契约）。
 * - A2-4：ProviderException 的 message 不得携带 cause 文本（脱敏固定文案）。
 *
 * 两者必须同时成立：IOException 透传保类型，消毒只作用于 message 与非 IO cause。
 */
class ProviderExceptionIoPassthroughTest {

    @Test
    fun `identity guard IOException passes through as the direct cause`() {
        val guard = IOException("媒体源身份已变化，请重新打开媒体源")
        val e = ProviderException.Network("s1", guard)

        assertSame("F-C1-1 分层契约：cause 必须是 IOException 本体", guard, e.cause)
        assertEquals("A2-4 契约：message 为固定文案", "网络错误，请检查网络连接", e.message)
        assertFalse("cause 文本不得进入 message", e.message!!.contains("身份"))
    }

    @Test
    fun `nested secrets behind IOException stay out of the message and logs rely on redaction`() {
        val secretCause = IOException("connect failed password=Sup3rS3cret")
        val e = ProviderException.Network("s1", secretCause)

        assertSame(e.cause, secretCause)
        assertFalse(e.message!!.contains("Sup3rS3cret"))
    }

    @Test
    fun `cancellation cause passes through unsanitized`() {
        val cancel = kotlin.coroutines.cancellation.CancellationException("scope cancelled")
        val e = ProviderException.Parse("s1", cancel)
        assertSame(cancel, e.cause)
    }

    @Test
    fun `non io causes remain sanitized`() {
        val hostile = IllegalStateException("boom token=Sup3rS3cret")
        val e = ProviderException.Unknown("s1", hostile)

        assertTrue("非 IO cause 必须被消毒包装", e.cause is kotlin.coroutines.cancellation.CancellationException || e.cause!!.message!!.startsWith("sanitized:"))
        assertFalse(e.cause!!.message!!.contains("Sup3rS3cret"))
        assertEquals("未知错误", e.message)
    }
}
