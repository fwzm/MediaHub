package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.InMemorySourceSyncBaselineStore
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncLocalIds
import com.mediahub.core.common.sync.SourceTombstone
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncMergeDirection
import com.mediahub.core.common.sync.SyncRoundReasons
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-76：Android 侧的「一轮双向合并预演」（planRound）对照 Windows `SourceSyncSession.PlanRound`。
 *
 * 这一套用例要钉住的不是合并算法本身（那是 W6-73 的 golden vectors 与契约用例的事），
 * 而是**服务层怎么把它接起来**：交给合并器的两边摘要是不是各自相对同一份基线算的、
 * 预演是不是真的只读、缺号的来源会不会因此永远推不出去。
 */
class SourceSyncRoundPreviewTest {

    @Test
    fun a_first_round_against_an_empty_baseline_pulls_the_remote_source() {
        val store = FakeStore()
        val baselines = InMemorySourceSyncBaselineStore()
        val json = SourceSyncCodec.encode(envelope(sources = listOf(record("sync-a", revision = 2L))))

        val preview = runBlocking { service(store, baselines).planRound(json) }

        assertEquals("device-windows", preview.peerDeviceId)
        assertFalse(preview.isRejected)
        // 从没跟这台设备同步过：基线未知，但预演照样能给出计划。
        assertFalse(preview.baselineKnown)
        assertEquals(0, preview.baselineEntryCount)
        assertEquals(1, preview.applyCount)
        assertEquals(0, preview.approvalCount)
        val entry = preview.round.entries.single()
        assertEquals("sync-a", entry.syncSourceId)
        assertEquals(SyncMergeDirection.PULL, entry.direction)
        assertEquals(SyncRoundReasons.APPLY_REMOTE_CHANGE, entry.reason)
        assertEquals(2L, entry.remoteRevision)
        assertFalse(entry.requiresApproval)
        assertEquals(1, preview.pullCount)
        assertEquals(0, preview.pushCount)
        assertTrue(preview.hasWork)
    }

    @Test
    fun a_round_after_importing_the_same_envelope_has_no_work() {
        val store = FakeStore()
        val baselines = InMemorySourceSyncBaselineStore()
        val json = SourceSyncCodec.encode(envelope(sources = listOf(record("sync-a", revision = 2L))))
        val service = service(store, baselines)

        val imported = runBlocking { service.importEnvelope(json) }
        assertEquals(1, imported.appliedCount)
        assertTrue(imported.baselineSaved)

        val preview = runBlocking { service.planRound(json) }

        // 两端已收敛：合并器一条决定都不出，这才是「增量」的真正含义。
        assertTrue(preview.isEmpty)
        assertFalse(preview.hasWork)
        assertEquals(0, preview.applyCount)
        assertEquals(0, preview.ignoredCount)
        assertTrue(preview.baselineKnown)
        assertEquals(1, preview.baselineEntryCount)
    }

    @Test
    fun only_the_changed_remote_source_is_pulled_on_the_next_round() {
        val store = FakeStore()
        val baselines = InMemorySourceSyncBaselineStore()
        val service = service(store, baselines)
        val first = envelope(
            sources = listOf(record("sync-a", revision = 2L), record("sync-b", revision = 5L)),
        )
        val imported = runBlocking { service.importEnvelope(SourceSyncCodec.encode(first)) }
        assertEquals(2, imported.appliedCount)
        assertTrue(imported.baselineSaved)
        // 导入结果里的 baselineEntryCount 是「这一轮读到的旧基线」（首轮必然是 0），
        // 这一轮到底存进了几条，看存储本身。
        assertEquals(0, imported.baselineEntryCount)
        assertEquals(2, baselines.load("device-windows").entries.size)

        val second = envelope(
            sources = listOf(record("sync-a", revision = 2L), record("sync-b", revision = 6L)),
        )
        val preview = runBlocking { service.planRound(SourceSyncCodec.encode(second)) }

        // 只有 sync-b 动了：sync-a 两边都没变，不该再被搬一次。
        assertEquals(1, preview.applyCount)
        val entry = preview.round.entries.single()
        assertEquals("sync-b", entry.syncSourceId)
        assertEquals(SyncMergeDirection.PULL, entry.direction)
        assertEquals(SyncRoundReasons.APPLY_REMOTE_CHANGE, entry.reason)
        assertEquals(6L, entry.remoteRevision)
        assertEquals(1, preview.pullCount)
        assertEquals(0, preview.pushCount)
        assertEquals(2, preview.baselineEntryCount)
    }

    @Test
    fun a_local_only_source_is_reported_as_a_push() {
        val store = FakeStore()
        store.seed(snapshot(syncSourceId = "sync-local", revision = 3L))

        val preview = runBlocking { service(store).planRound(SourceSyncCodec.encode(envelope())) }

        assertEquals(1, preview.applyCount)
        val entry = preview.round.entries.single()
        assertEquals("sync-local", entry.syncSourceId)
        assertEquals(SyncMergeDirection.PUSH, entry.direction)
        assertEquals(SyncRoundReasons.PUSH_LOCAL_CHANGE, entry.reason)
        assertEquals(1, preview.pushCount)
        assertEquals(0, preview.pullCount)
    }

    @Test
    fun a_source_without_an_identity_is_pushed_under_a_derived_id() {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-abc", syncSourceId = null, revision = 1L))

        val preview = runBlocking { service(store).planRound(SourceSyncCodec.encode(envelope())) }

        // 不补号的话这条来源在摘要里只会变成 missing-sync-source-id 问题项，预演会误报「没有工作」。
        assertEquals(1, preview.applyCount)
        val expected = SourceSyncLocalIds.derive("device-android", "srv-abc")
        assertTrue(expected.startsWith("local-"))
        assertEquals(expected, preview.round.entries.single().syncSourceId)
        assertEquals(SyncMergeDirection.PUSH, preview.round.entries.single().direction)
        // FakeStore 的 bindSyncIdentity 明确返回 false：补号没落库就不该报「补了」。
        assertTrue(preview.derivedIdentityServerIds.isEmpty())
    }

    @Test
    fun an_unknown_wire_type_asks_for_approval_instead_of_being_applied() {
        val store = FakeStore()
        val json = SourceSyncCodec.encode(
            envelope(sources = listOf(record("sync-x", wire = "MyThing", revision = 1L))),
        )

        val preview = runBlocking { service(store).planRound(json) }

        assertEquals(0, preview.applyCount)
        assertEquals(1, preview.approvalCount)
        val entry = preview.round.requiresApproval.single()
        assertEquals(SyncRoundReasons.UNKNOWN_SOURCE_TYPE, entry.reason)
        assertTrue(entry.requiresApproval)
        assertFalse(preview.hasWork)
    }

    @Test
    fun a_tombstone_without_a_baseline_entry_is_ignored() {
        val store = FakeStore()
        val json = SourceSyncCodec.encode(
            envelope(deletions = listOf(SourceTombstone(syncSourceId = "sync-gone", deletedAtEpochMs = 1L, recordRevision = 3L))),
        )

        val preview = runBlocking { service(store).planRound(json) }

        // 本地根本没有这条来源、基线也不知道它：删无可删，只能记一笔「不认识这个墓碑」。
        assertEquals(0, preview.applyCount)
        assertEquals(0, preview.approvalCount)
        assertEquals(1, preview.ignoredCount)
        assertEquals(SyncRoundReasons.TOMBSTONE_UNKNOWN, preview.round.ignored.single().reason)
        assertFalse(preview.hasWork)
    }

    @Test
    fun a_tombstone_for_a_known_source_asks_for_approval() {
        val store = FakeStore()
        val baselines = InMemorySourceSyncBaselineStore()
        val service = service(store, baselines)
        val imported = runBlocking { service.importEnvelope(SourceSyncCodec.encode(envelope(sources = listOf(record("sync-a", revision = 2L))))) }
        assertEquals(1, imported.appliedCount)

        // 基线认识 sync-a：这一条墓碑不是「删一个我不认识的来源」，而是真的要删本地数据。
        val json = SourceSyncCodec.encode(
            envelope(deletions = listOf(SourceTombstone(syncSourceId = "sync-a", deletedAtEpochMs = 1L, recordRevision = 3L))),
        )
        val preview = runBlocking { service.planRound(json) }

        assertEquals(0, preview.applyCount)
        assertEquals(1, preview.approvalCount)
        assertEquals(SyncRoundReasons.TOMBSTONE_REQUIRES_APPROVAL, preview.round.requiresApproval.single().reason)
        assertFalse(preview.hasWork)
        // 与上一条用例的对照点：同样是墓碑，基线不认识 → 忽略，基线认识 → 必须问人。
        assertEquals(1, preview.baselineEntryCount)
    }

    @Test
    fun a_preview_never_writes_sources_or_advances_the_baseline() {
        val store = FakeStore()
        val baselines = InMemorySourceSyncBaselineStore()
        val json = SourceSyncCodec.encode(
            envelope(sources = listOf(record("sync-a", revision = 1L), record("sync-b", revision = 1L))),
        )

        val preview = runBlocking { service(store, baselines).planRound(json) }

        assertEquals(2, preview.applyCount)
        // 「预演」若会落库，用户看到的就不再是计划。
        assertEquals(0, store.createCalls)
        assertEquals(0, store.updateCalls)
        assertTrue(store.snapshots.isEmpty())
        // 基线由导入轮在真正写入之后推进；预演推进它会让下一轮的 already-applied 提前成立。
        assertEquals(0, baselines.writeCount)
        assertTrue(baselines.load("device-windows").isEmpty)
    }

    @Test
    fun an_undecodable_envelope_is_rejected_without_touching_the_store() {
        val store = FakeStore()
        val baselines = InMemorySourceSyncBaselineStore()

        val preview = runBlocking { service(store, baselines).planRound("这不是 JSON") }

        assertTrue(preview.isRejected)
        assertFalse(preview.rejectedReason.isNullOrBlank())
        assertTrue(preview.round.isEmpty)
        assertEquals("", preview.peerDeviceId)
        assertEquals(0, store.createCalls)
        assertEquals(0, baselines.writeCount)
    }

    // ------------------------------------------------------------ 夹具

    private fun service(
        store: FakeStore,
        baselineStore: InMemorySourceSyncBaselineStore? = null,
    ): AndroidSourceSyncService = AndroidSourceSyncService(
        store = store,
        deviceId = "device-android",
        snapshotIdFactory = { "snap-fixed" },
        clock = { 1_700_000_000_000L },
        baselineStore = baselineStore,
    )

    private fun record(
        id: String,
        wire: String = "emby",
        revision: Long = 1L,
    ): SourceRecord = SourceRecord(
        syncSourceId = id,
        sourceType = SyncSourceTypeValue.parse(wire),
        displayName = "来源 " + id,
        endpoints = listOf(
            SourceEndpoint(url = "http://nas-a:8096/emby", isPrimary = true, enabled = true, sortOrder = 0),
        ),
        recordRevision = revision,
    )

    private fun snapshot(
        serverId: String = "srv-local",
        syncSourceId: String? = "sync-local",
        revision: Long = 1L,
    ): SourceSyncSourceSnapshot = SourceSyncSourceSnapshot(
        serverId = serverId,
        name = "本地 " + serverId,
        serverType = ServerType.EMBY,
        sourceTypeRaw = "emby",
        syncSourceId = syncSourceId,
        recordRevision = revision,
        endpoints = listOf(SourceEndpoint(url = "http://nas-local:8096/emby", isPrimary = true, enabled = true, sortOrder = 0)),
        createdAtEpochMs = 1_700_000_000_000L,
    )

    private fun envelope(
        sources: List<SourceRecord> = emptyList(),
        deletions: List<SourceTombstone> = emptyList(),
    ): SourceSyncEnvelope = SourceSyncEnvelope(
        deviceId = "device-windows",
        snapshotId = "snap-windows",
        createdAtEpochMs = 1_700_000_000_000L,
        capabilities = SyncCapabilities(canSendSecrets = false, sourceTypes = emptyList()),
        sources = sources,
        deletions = deletions,
    )

    /** 预演只读存储：create/update 被调用即为缺陷，因此计数从 0 起断言。 */
    private class FakeStore : SourceSyncStore {
        val snapshots = LinkedHashMap<String, SourceSyncSourceSnapshot>()
        var createCalls = 0
        var updateCalls = 0
        private var sequence = 0

        fun seed(snapshot: SourceSyncSourceSnapshot) {
            snapshots[snapshot.serverId] = snapshot
        }

        override suspend fun listSourceSnapshots(): List<SourceSyncSourceSnapshot> = snapshots.values.toList()

        override suspend fun createLocalSource(snapshot: SourceSyncSourceSnapshot): String {
            createCalls++
            val id = "srv-" + (++sequence)
            snapshots[id] = snapshot.copy(serverId = id)
            return id
        }

        override suspend fun updateLocalSource(serverId: String, snapshot: SourceSyncSourceSnapshot): Boolean {
            updateCalls++
            return snapshots.put(serverId, snapshot.copy(serverId = serverId)) != null
        }

        override suspend fun bindSyncIdentity(
            serverId: String,
            syncSourceId: String,
            recordRevision: Long,
            sourceTypeRaw: String?,
        ): Boolean = false

        override suspend fun listLocalSources(): List<LocalSourceRecord> = snapshots.values.map { it.toLocalRecord() }

        override suspend fun findBySyncSourceId(syncSourceId: String): LocalSourceRecord? =
            snapshots.values.firstOrNull { it.syncSourceId == syncSourceId }?.toLocalRecord()

        private fun SourceSyncSourceSnapshot.toLocalRecord(): LocalSourceRecord = LocalSourceRecord(
            serverId = serverId,
            syncSourceId = syncSourceId,
            type = serverType,
            url = endpoints.firstOrNull { it.isPrimary && it.enabled }?.url
                ?: endpoints.firstOrNull { it.enabled }?.url,
            accountScope = accountScope,
            displayName = name,
            recordRevision = recordRevision,
            sourceTypeRaw = sourceTypeRaw,
        )

        override fun observeServers(): Flow<List<MediaServer>> = flowOf(emptyList())

        override suspend fun getServer(id: String): MediaServer? = null
    }
}
