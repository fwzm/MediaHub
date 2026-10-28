package com.mediahub.feature.server

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.database.sync.SourceSyncRemovalCodes
import com.mediahub.core.database.sync.SourceSyncRemovalStep
import com.mediahub.core.database.sync.SourceSyncRemoveOutcome
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W6-79：跨端删除的生产实现用例。
 *
 * 这里钉三件事：删除真的走了完整删除链、找不到/空白身份报 [SourceSyncRemoveOutcome.Missing] 而不是「失败」、
 * 删除链中途失败时原因码能说出卡在哪一步（W6-88）。级联清理本身由 [RemoveServerUseCase] 自己的用例负责
 * （`SourceSyncRemovalCascadeRoomTest` 与 `RemoveServerUseCaseStepsTest`），不在这里重复。
 */
class ServerSourceSyncRemoverTest {

    @Test
    fun a_known_source_is_removed_through_the_full_remove_chain() = runTest {
        val server = serverOf("srv-1")
        val store = FakeServerStore(listOf(server))
        val handler = FakeRemoveHandler()
        val remover = ServerSourceSyncRemover(store, handler)

        assertEquals(SourceSyncRemoveOutcome.Removed, remover.removeSource("srv-1"))
        assertEquals(listOf(server), handler.removed)
    }

    @Test
    fun the_identity_is_trimmed_before_lookup() = runTest {
        val server = serverOf("srv-1")
        val store = FakeServerStore(listOf(server))
        val handler = FakeRemoveHandler()

        assertEquals(SourceSyncRemoveOutcome.Removed, ServerSourceSyncRemover(store, handler).removeSource("  srv-1  "))
        assertEquals(listOf(server), handler.removed)
    }

    @Test
    fun an_unknown_source_is_reported_as_missing_and_the_chain_is_never_touched() = runTest {
        val handler = FakeRemoveHandler()
        val remover = ServerSourceSyncRemover(FakeServerStore(emptyList()), handler)

        // 「本机没有」不是故障：链一次都不该走，回执也不能写成 failed。
        assertEquals(SourceSyncRemoveOutcome.Missing, remover.removeSource("srv-gone"))
        assertEquals(emptyList<MediaServer>(), handler.removed)
    }

    @Test
    fun a_blank_identity_is_never_removed() = runTest {
        val handler = FakeRemoveHandler()
        val remover = ServerSourceSyncRemover(FakeServerStore(listOf(serverOf("srv-1"))), handler)

        assertEquals(SourceSyncRemoveOutcome.Missing, remover.removeSource("   "))
        assertEquals(emptyList<MediaServer>(), handler.removed)
    }

    @Test
    fun a_failing_remove_chain_keeps_the_plain_failure_code_when_no_step_is_known() = runTest {
        val handler = FakeRemoveHandler().apply { failure = IllegalStateException("vault locked") }
        val remover = ServerSourceSyncRemover(FakeServerStore(listOf(serverOf("srv-1"))), handler)

        assertEquals(
            SourceSyncRemoveOutcome.Failed(SourceSyncRemovalCodes.FAILED),
            remover.removeSource("srv-1"),
        )
    }

    @Test
    fun a_failed_step_names_itself_in_the_reason_code() = runTest {
        val handler = FakeRemoveHandler().apply {
            failure = RemoveServerFailedException(SourceSyncRemovalStep.SESSION_STORE, IOException("socket closed"))
        }
        val remover = ServerSourceSyncRemover(FakeServerStore(listOf(serverOf("srv-1"))), handler)

        val outcome = remover.removeSource("srv-1")

        assertEquals(
            SourceSyncRemoveOutcome.Failed("local-source-remove-failed:session-store"),
            outcome,
        )
        assertEquals(
            "session-store",
            SourceSyncRemovalCodes.stepCodeOf((outcome as SourceSyncRemoveOutcome.Failed).reason),
        )
    }

    @Test
    fun two_different_steps_never_share_a_reason_code() = runTest {
        val credentials = ServerSourceSyncRemover(
            FakeServerStore(listOf(serverOf("srv-1"))),
            FakeRemoveHandler().apply {
                failure = RemoveServerFailedException(SourceSyncRemovalStep.CREDENTIALS, IOException("keyring"))
            },
        ).removeSource("srv-1")
        val progress = ServerSourceSyncRemover(
            FakeServerStore(listOf(serverOf("srv-1"))),
            FakeRemoveHandler().apply {
                failure = RemoveServerFailedException(SourceSyncRemovalStep.PROGRESS, IOException("db busy"))
            },
        ).removeSource("srv-1")

        assertEquals(
            SourceSyncRemoveOutcome.Failed("local-source-remove-failed:credentials"),
            credentials,
        )
        assertEquals(
            SourceSyncRemoveOutcome.Failed("local-source-remove-failed:progress"),
            progress,
        )
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
