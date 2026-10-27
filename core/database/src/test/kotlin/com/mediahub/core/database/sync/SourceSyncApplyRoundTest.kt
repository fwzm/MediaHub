package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.InMemorySourceSyncBaselineStore
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceTombstone
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncRoundReasons
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-78：Android 侧的「一轮落库」（applyRound）对照 Windows `SourceSyncImportRound.ApplyAsync`。
 *
 * 这一套要钉住的不是合并与闸门的判据（那是 W6-73/77 的向量与契约用例的事），而是**服务层怎么把
 * 批准结果落到存储上**：批准了才写、写不下去要如实拒绝、基线只在本轮真的写进库之后推进。
 *
 * 每条用例的写入路径都走真实的 [AndroidSourceSyncService]，存储与基线都是内存假件，
 * 因此断言的是「调用方看到的回执」与「库里的实际行」，不是被 stub 过的中间层。
 */
class SourceSyncApplyRoundTest {

    @Test
    fun a_plain_pull_is_written_by_default_and_the_baseline_advances() = runBlocking {
        val store = FakeStore()
        val baselines = InMemorySourceSyncBaselineStore()
        val service = service(store, baselines)
        val json = SourceSyncCodec.encode(envelope(sources = listOf(record("sync-a", revision = 3L))))

        val report = service.applyRound(json)

        assertEquals(1, report.applied)
        assertEquals(0, report.skipped)
        assertEquals(0, report.withheld)
        assertEquals(0, report.refused)
        assertEquals(1, report.total)
        assertEquals(1, store.createCalls)
        assertEquals(listOf("srv-1"), report.wroteServerIds)
        assertTrue(report.hasWrites)
        assertEquals("已写入本地 1 条 / 跳过 0 条 / 保留未应用 0 条", report.summary)
        // 基线只在这一轮真的写进去之后推进，且条数取自保存后的真实值。
        assertTrue(report.baselineUpdated)
        assertEquals(1, report.baselineEntryCount)
        assertEquals(1, baselines.writeCount)
        assertEquals("sync-a", store.snapshots["srv-1"]?.syncSourceId)
    }

    @Test
    fun an_entry_that_needs_approval_is_skipped_until_it_is_allowed() = runBlocking {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "sync-a", revision = 5L))
        val json = SourceSyncCodec.encode(envelope(sources = listOf(record("sync-a", revision = 5L))))

        val byDefault = service(store).applyRound(json)
        assertEquals(0, byDefault.applied)
        assertEquals(1, byDefault.skipped)
        assertEquals(0, store.updateCalls)
        assertEquals(0, byDefault.total - byDefault.skipped)

        val allowed = service(store).applyRound(json, allowedSyncSourceIds = listOf("sync-a"))
        assertEquals(1, allowed.applied)
        assertEquals(0, allowed.skipped)
        assertEquals(1, store.updateCalls)
        // 要人点头才放行的条目被放行，回执必须声明「有人承担了这个风险」。
        assertTrue(allowed.acknowledgedRisk)
        assertEquals(listOf("srv-local"), allowed.wroteServerIds)
    }

    @Test
    fun a_record_carrying_credentials_is_withheld_even_when_it_is_allowed() = runBlocking {
        val store = FakeStore()
        val json = SourceSyncCodec.encode(
            envelope(sources = listOf(record("sync-a", secrets = JsonPrimitive("sealed-blob")))),
        )

        val report = service(store).applyRound(json, allowedSyncSourceIds = listOf("sync-a"))

        assertEquals(0, report.applied)
        assertEquals(1, report.withheld)
        assertEquals(0, report.refused)
        assertEquals(listOf(SyncRoundReasons.SECRETS_REQUIRE_APPROVAL), report.blockingReasons)
        assertEquals(0, store.createCalls)
        assertFalse(report.hasWrites)
    }

    @Test
    fun an_unknown_source_type_is_withheld_even_when_it_is_allowed() = runBlocking {
        val store = FakeStore()
        val json = SourceSyncCodec.encode(
            envelope(sources = listOf(record("sync-a", wire = "MyThing"))),
        )

        val report = service(store).applyRound(json, allowedSyncSourceIds = listOf("sync-a"))

        assertEquals(0, report.applied)
        assertEquals(1, report.withheld)
        assertEquals(listOf(SyncRoundReasons.UNKNOWN_SOURCE_TYPE), report.blockingReasons)
        assertEquals(0, store.createCalls)
    }

    @Test
    fun a_deletion_is_refused_because_the_store_cannot_delete() = runBlocking {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "sync-del", revision = 1L))
        val json = SourceSyncCodec.encode(
            envelope(
                deletions = listOf(
                    SourceTombstone(
                        syncSourceId = "sync-del",
                        deletedAtEpochMs = 1_700_000_100_000L,
                        recordRevision = 2L,
                    ),
                ),
            ),
        )

        val report = service(store).applyRound(json, allowedSyncSourceIds = listOf("sync-del"))

        assertEquals(0, report.applied)
        assertEquals(1, report.refused)
        assertEquals(listOf("sync-del"), report.refusedSyncSourceIds)
        assertTrue(report.isRefused(" sync-del "))
        // 库里那一行必须原封不动：墓碑不删本地来源（ADR-S001），拒绝就是拒绝，不能假装应用过。
        assertEquals(1, store.snapshots.size)
        assertEquals(0, store.updateCalls)
        assertFalse(report.baselineUpdated)
    }

    @Test
    fun skipping_an_identity_outranks_allowing_it() = runBlocking {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "sync-a", revision = 5L))
        val json = SourceSyncCodec.encode(envelope(sources = listOf(record("sync-a", revision = 5L))))

        val report = service(store).applyRound(
            json,
            allowedSyncSourceIds = listOf("sync-a"),
            skippedSyncSourceIds = listOf(" sync-a "),
        )

        assertEquals(0, report.applied)
        assertEquals(1, report.skipped)
        assertEquals(0, store.updateCalls)
    }

    @Test
    fun the_baseline_only_carries_the_records_that_were_actually_written() = runBlocking {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "sync-b", revision = 5L))
        val baselines = InMemorySourceSyncBaselineStore()
        val json = SourceSyncCodec.encode(
            envelope(
                sources = listOf(
                    record("sync-a", revision = 2L),
                    record("sync-b", revision = 5L),
                ),
            ),
        )

        val report = service(store, baselines).applyRound(json)

        // sync-b 要人确认（同版本不同内容），默认跳过；只有 sync-a 进基线。
        assertEquals(1, report.applied)
        assertEquals(1, report.skipped)
        assertEquals(2, report.total)
        assertTrue(report.baselineUpdated)
        assertTrue(report.warnings.isEmpty())
        assertEquals(1, report.baselineEntryCount)
        assertEquals(
            listOf("sync-a"),
            baselines.load("device-windows").entries.map { it.syncSourceId },
        )
    }

    @Test
    fun a_storage_failure_is_reported_as_refused_instead_of_thrown() = runBlocking {
        val store = FakeStore()
        store.failWrites = true
        val baselines = InMemorySourceSyncBaselineStore()
        val json = SourceSyncCodec.encode(envelope(sources = listOf(record("sync-a", revision = 2L))))

        val report = service(store, baselines).applyRound(json)

        assertEquals(0, report.applied)
        assertEquals(1, report.refused)
        assertEquals(listOf("sync-a"), report.refusedSyncSourceIds)
        assertFalse(report.baselineUpdated)
        // 一条都没写进库 ⇒ 基线一个字都不许动。
        assertEquals(0, baselines.writeCount)
        assertEquals(1, report.total)
    }

    @Test
    fun an_identity_matches_a_local_row_regardless_of_spelling() = runBlocking {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "sync-a", revision = 1L))
        val json = SourceSyncCodec.encode(envelope(sources = listOf(record("SYNC-A", revision = 4L))))

        // 两侧内容不同而 fieldVersion 相同 ⇒ 合并器判字段冲突（要人裁决），所以这里显式放行；
        // 本条要验的是「放行之后按身份找到那一行去更新」，不是默认裁决。
        val report = service(store).applyRound(json, allowedSyncSourceIds = listOf("SYNC-A"))

        assertEquals(1, report.applied)
        assertTrue(report.acknowledgedRisk)
        assertEquals(1, store.updateCalls)
        assertEquals(0, store.createCalls)
        // 大小写不同也是同一条来源：库里不许留下第二个身份。
        assertEquals(1, store.snapshots.size)
        assertEquals("SYNC-A", store.snapshots["srv-local"]?.syncSourceId)
    }

    @Test
    fun an_undecodable_envelope_is_rejected_without_touching_the_store() = runBlocking {
        val store = FakeStore()
        val baselines = InMemorySourceSyncBaselineStore()

        val report = service(store, baselines).applyRound("这不是 JSON")

        assertTrue(report.isRejected)
        assertEquals(0, report.total)
        assertTrue(report.isEmpty)
        assertEquals(0, store.createCalls)
        assertEquals(0, store.updateCalls)
        assertEquals(0, baselines.writeCount)
        assertEquals("", report.peerDeviceId)
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
        secrets: kotlinx.serialization.json.JsonElement? = null,
    ): SourceRecord = SourceRecord(
        syncSourceId = id,
        sourceType = SyncSourceTypeValue.parse(wire),
        displayName = "来源 " + id,
        endpoints = listOf(
            SourceEndpoint(url = "http://nas-a:8096/emby", isPrimary = true, enabled = true, sortOrder = 0),
        ),
        recordRevision = revision,
        secrets = secrets,
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
        endpoints = listOf(
            SourceEndpoint(
                url = "http://nas-local:8096/emby",
                isPrimary = true,
                enabled = true,
                sortOrder = 0,
            ),
        ),
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

    /** 内存存储：计数调用次数，另有一条「写入必失败」的开关用来测拒绝回执。 */
    private class FakeStore : SourceSyncStore {
        val snapshots = LinkedHashMap<String, SourceSyncSourceSnapshot>()
        var createCalls = 0
        var updateCalls = 0
        var failWrites = false
        private var sequence = 0

        fun seed(snapshot: SourceSyncSourceSnapshot) {
            snapshots[snapshot.serverId] = snapshot
        }

        override suspend fun listSourceSnapshots(): List<SourceSyncSourceSnapshot> =
            snapshots.values.toList()

        override suspend fun createLocalSource(snapshot: SourceSyncSourceSnapshot): String {
            if (failWrites) throw IllegalStateException("disk full")
            createCalls++
            val id = "srv-" + (++sequence)
            snapshots[id] = snapshot.copy(serverId = id)
            return id
        }

        override suspend fun updateLocalSource(
            serverId: String,
            snapshot: SourceSyncSourceSnapshot,
        ): Boolean {
            if (failWrites) throw IllegalStateException("disk full")
            updateCalls++
            return snapshots.put(serverId, snapshot.copy(serverId = serverId)) != null
        }

        override suspend fun bindSyncIdentity(
            serverId: String,
            syncSourceId: String,
            recordRevision: Long,
            sourceTypeRaw: String?,
        ): Boolean = false

        override suspend fun listLocalSources(): List<LocalSourceRecord> =
            snapshots.values.map { it.toLocalRecord() }

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
