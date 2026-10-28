package com.mediahub.core.database.sync

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W6-98：删除链失败的那句异常消息怎么变成能摆在单行上的原因句。
 *
 * 这里钉的是「不编造」：没有消息就是 null，不出现空串或「未知原因」；有消息就折叠成一行、
 * 超长截断，且截断不许切在代理对中间。回执与界面两侧都依赖这四条，所以它落在写码同层的 core:database。
 */
class SourceSyncRemovalCausesTest {

    @Test
    fun a_missing_cause_or_a_message_that_says_nothing_becomes_null() {
        assertNull(SourceSyncRemovalCauses.describe(null))
        assertNull(SourceSyncRemovalCauses.describe(IllegalStateException()))
        assertNull(SourceSyncRemovalCauses.describe(IllegalStateException("   ")))
        assertNull(SourceSyncRemovalCauses.describe(IllegalStateException("\n\t  \r\n")))
    }

    @Test
    fun the_message_is_trimmed_and_folded_into_one_line() {
        assertEquals(
            "vault locked during cleanup",
            SourceSyncRemovalCauses.describe(IOException("vault locked\n\tduring\n cleanup  ")),
        )
        // 消息本身只有空白的异常（折叠后仍为空）与没有消息一视同仁。
        assertNull(SourceSyncRemovalCauses.describe(IOException(" \n \t ")))
    }

    @Test
    fun a_message_at_the_cap_is_kept_whole_and_one_over_is_truncated() {
        val atCap = "x".repeat(SourceSyncRemovalCauses.MAXIMUM_MESSAGE_CHARS)
        assertEquals(atCap, SourceSyncRemovalCauses.describe(IOException(atCap)))

        val over = "y".repeat(SourceSyncRemovalCauses.MAXIMUM_MESSAGE_CHARS + 1)
        val cut = SourceSyncRemovalCauses.describe(IOException(over))!!
        assertEquals("y".repeat(SourceSyncRemovalCauses.MAXIMUM_MESSAGE_CHARS) + "…", cut)
        assertEquals(SourceSyncRemovalCauses.MAXIMUM_MESSAGE_CHARS + 1, cut.length)
    }

    @Test
    fun truncation_never_cuts_a_surrogate_pair_in_half() {
        // 第 160 个 UTF-16 码元正好是 emoji 的高代理：直接 take(160) 会留下半个字符。
        val message = "a".repeat(SourceSyncRemovalCauses.MAXIMUM_MESSAGE_CHARS - 1) + "\uD83D\uDE00" + "b"

        assertEquals(
            "a".repeat(SourceSyncRemovalCauses.MAXIMUM_MESSAGE_CHARS - 1) + "…",
            SourceSyncRemovalCauses.describe(IOException(message)),
        )
    }
}
