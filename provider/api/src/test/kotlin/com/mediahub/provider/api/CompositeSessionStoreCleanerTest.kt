package com.mediahub.provider.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 组合会话清理器（ADR-039 review hardening）：聚合 `@IntoSet` 贡献的
 * ProviderSessionCleaner——app composition root 不枚举具体 Provider，
 * 新 Provider 零 app 改动接入。
 */
class CompositeSessionStoreCleanerTest {

    private class RecordingCleaner(private val name: String, private val calls: MutableList<Pair<String, String>>) :
        ProviderSessionCleaner {
        override suspend fun clear(serverId: String) {
            calls += name to serverId
        }
    }

    @Test
    fun `every contributed cleaner receives the same server id`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val composite = CompositeSessionStoreCleaner(
            setOf(
                RecordingCleaner("emby", calls),
                RecordingCleaner("jellyfin", calls),
            ),
        )

        composite.clear("srv-7")

        assertEquals(setOf("emby" to "srv-7", "jellyfin" to "srv-7"), calls.toSet())
    }

    @Test
    fun `empty contributed set is a safe no-op`() = runBlocking {
        CompositeSessionStoreCleaner(emptySet()).clear("srv-1")
    }
}
