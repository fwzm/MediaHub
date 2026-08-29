package com.mediahub.provider.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 组合会话清理器（ADR-039）：全部 delegate 按序收到同一 serverId，删除媒体源零残留。 */
class CompositeSessionStoreCleanerTest {

    @Test
    fun `every delegated cleaner receives the same server id in order`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val composite = CompositeSessionStoreCleaner(
            listOf(
                SessionStoreCleaner { calls += "emby" to it },
                SessionStoreCleaner { calls += "jellyfin" to it },
            ),
        )

        composite.clear("srv-7")

        assertEquals(listOf("emby" to "srv-7", "jellyfin" to "srv-7"), calls)
    }

    @Test
    fun `empty composite is a safe no-op`() = runBlocking {
        CompositeSessionStoreCleaner(emptyList()).clear("srv-1")
    }
}
