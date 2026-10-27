package com.mediahub.feature.server

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-79：跨端删除的生产实现用例。
 *
 * 这里只钉「删除真的走了完整删除链」和「找不到/失败不抛」两条；级联清理本身由
 * `RemoveServerUseCase` 自己的用例负责，不在这里重复。
 */
class ServerSourceSyncRemoverTest {

    @Test
    fun a_known_source_is_removed_through_the_full_remove_chain() = runTest {
        val server = serverOf("srv-1")
        val store = FakeServerStore(listOf(server))
        val handler = FakeRemoveHandler()
        val remover = ServerSourceSyncRemover(store, handler)

        assertTrue(remover.removeSource("srv-1"))
        assertEquals(listOf(server), handler.removed)
    }

    @Test
    fun the_identity_is_trimmed_before_lookup() = runTest {
        val server = serverOf("srv-1")
        val store = FakeServerStore(listOf(server))
        val handler = FakeRemoveHandler()

        assertTrue(ServerSourceSyncRemover(store, handler).removeSource("  srv-1  "))
        assertEquals(listOf(server), handler.removed)
    }

    @Test
    fun an_unknown_source_is_reported_as_not_removed_and_the_chain_is_never_touched() = runTest {
        val handler = FakeRemoveHandler()
        val remover = ServerSourceSyncRemover(FakeServerStore(emptyList()), handler)

        assertFalse(remover.removeSource("srv-gone"))
        assertEquals(emptyList<MediaServer>(), handler.removed)
    }

    @Test
    fun a_blank_identity_is_never_removed() = runTest {
        val handler = FakeRemoveHandler()
        val remover = ServerSourceSyncRemover(FakeServerStore(listOf(serverOf("srv-1"))), handler)

        assertFalse(remover.removeSource("   "))
        assertEquals(emptyList<MediaServer>(), handler.removed)
    }

    @Test
    fun a_failing_remove_chain_is_reported_as_not_removed_instead_of_thrown() = runTest {
        val handler = FakeRemoveHandler().apply { failure = IllegalStateException("vault locked") }
        val remover = ServerSourceSyncRemover(FakeServerStore(listOf(serverOf("srv-1"))), handler)

        assertFalse(remover.removeSource("srv-1"))
    }

    // ------------------------------------------------------------ 夹具

    private fun serverOf(id: String) = MediaServer(
        id = id,
        name = "客厅 Emby",
        type = ServerType.EMBY,
        createdAtEpochMs = 1_700_000_000_000L,
    )

    private class FakeServerStore(private val servers: List<MediaServer>) : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(servers)

        override suspend fun getServer(id: String): MediaServer? = servers.firstOrNull { it.id == id }
    }

    private class FakeRemoveHandler : ServerRemoveHandler {
        val removed = ArrayList<MediaServer>()
        var failure: Exception? = null

        override suspend fun remove(server: MediaServer) {
            failure?.let { throw it }
            removed.add(server)
        }
    }
}
