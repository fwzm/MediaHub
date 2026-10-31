package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.InMemorySourceSyncBaselineStore
import com.mediahub.core.common.sync.ISourceSyncBaselineStore
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncBaseline
import com.mediahub.core.common.sync.SourceSyncBaselineSaveResult
import com.mediahub.core.common.sync.SourceSyncBaselineStoreReasons
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncContentHash
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncSourceTypeValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-72：把对端同步基线接进唯一写库入口（[AndroidSourceSyncService]）。
 *
 * 钉住的性质（对齐 Windows W6-10 的 SourceSyncImportRound）：
 * 1. 只把**真正写进库**的条目记进基线，未写/被拒的条目一条都不进；
 * 2. 一件都没写时不碰存储（不产生无意义的 Unchanged 写）；
 * 3. 基线存储抛异常绝不把导入本身变成失败（折成 baseline-write-failed 稳定码）；
 * 4. 没有基线存储时行为与 W6-71 完全一致（可选依赖，老调用方零改动）；
 * 5. 端点多于一个时如实汇报"次要端点未参与同步"的条数（不删本机线路）。
 *
 * 基线的磁盘格式与合并语义由 core:common 的契约测试（SourceSyncBaselineContractTest）逐字节钉住；
 * 这一层只钉"谁被记进去、什么时候不记"。
 */
class SourceSyncBaselineWiringTest {

    // ------------------------------------------------------------ 1. 只记真正写入的条目

    @Test
    fun only_written_entries_reach_the_peer_baseline() {
        val store = FakeStore()
        val baselineStore = InMemorySourceSyncBaselineStore()
        val result = runBlocking {
            service(store, baselineStore).importEnvelope(
                SourceSyncCodec.encode(
                    envelope(
                        record("sync-a", revision = 7L),
                        record("sync-b", revision = 3L),
                        // 未知类型：规划器判 UNSUPPORTED，永远不写库 => 绝不进基线。
                        record("sync-q", wire = "quantum-nas"),
                    ),
                ),
            )
        }

        assertEquals(2, result.appliedCount)
        assertTrue(result.baselineSaved)
        assertEquals(emptyList<String>(), result.baselineRejectedSyncSourceIds)
        assertNull(result.baselineRejectionReason)

        val saved = baselineStore.load("device-windows")
        assertEquals("device-windows", saved.deviceId)
        assertEquals("snap-windows", saved.snapshotId)
        assertEquals(listOf("sync-a", "sync-b"), saved.entries.map { it.syncSourceId })
        assertEquals(listOf(7L, 3L), saved.entries.map { it.recordRevision })
        // 基线里的内容哈希必须是 Windows 那套规范 JSON 的哈希（不是本端 SourceSyncFingerprints）。
        assertEquals(
            listOf(SourceSyncContentHash.compute(record("sync-a", revision = 7L)), SourceSyncContentHash.compute(record("sync-b", revision = 3L))),
            saved.entries.map { it.contentHash },
        )
        assertEquals(1, baselineStore.writeCount)
    }

    @Test
    fun a_second_import_without_writes_does_not_touch_the_store() {
        val store = FakeStore()
        val baselineStore = InMemorySourceSyncBaselineStore()
        val json = SourceSyncCodec.encode(envelope(record("sync-a")))
        val service = service(store, baselineStore)

        val first = runBlocking { service.importEnvelope(json) }
        val second = runBlocking { service.importEnvelope(json) }

        assertEquals(1, first.appliedCount)
        assertEquals(0, second.appliedCount)
        assertEquals(1, second.ignoredCount)
        // 第二次零写入：连 Unchanged 都不该产生（存储层一次都没被调用）。
        assertEquals(1, baselineStore.writeCount)
        assertEquals(1, baselineStore.load("device-windows").entries.size)
    }

    // ------------------------------------------------------------ 2. 存储失败绝不牵连导入

    @Test
    fun a_broken_baseline_store_never_fails_the_import() {
        val store = FakeStore()
        val baselineStore = object : ISourceSyncBaselineStore {
            override fun load(peerDeviceId: String): SourceSyncBaseline = SourceSyncBaseline.EMPTY
            override fun save(
                peerDeviceId: String,
                baseline: SourceSyncBaseline,
            ): SourceSyncBaselineSaveResult {
                throw java.io.IOException("disk is gone")
            }

            override fun forget(peerDeviceId: String): Boolean = throw java.io.IOException("disk is gone")
        }

        val result = runBlocking {
            service(store, baselineStore).importEnvelope(SourceSyncCodec.encode(envelope(record("sync-a"))))
        }

        assertFalse(result.isRejected)
        assertEquals(1, result.appliedCount)
        assertFalse(result.baselineSaved)
        assertEquals(SourceSyncBaselineStoreReasons.WRITE_FAILED, result.baselineRejectionReason)
    }

    @Test
    fun a_store_that_refuses_a_stale_entry_reports_the_ids() {
        val store = FakeStore()
        val baselineStore = object : ISourceSyncBaselineStore {
            override fun load(peerDeviceId: String): SourceSyncBaseline = SourceSyncBaseline.EMPTY
            override fun save(
                peerDeviceId: String,
                baseline: SourceSyncBaseline,
            ): SourceSyncBaselineSaveResult = SourceSyncBaselineSaveResult(
                saved = true,
                rejectedSyncSourceIds = listOf("sync-a"),
                reason = SourceSyncBaselineStoreReasons.STALE_REVISION,
            )

            override fun forget(peerDeviceId: String): Boolean = false
        }

        val result = runBlocking {
            service(store, baselineStore).importEnvelope(SourceSyncCodec.encode(envelope(record("sync-a"))))
        }

        assertEquals(1, result.appliedCount)
        assertTrue(result.baselineSaved)
        assertEquals(listOf("sync-a"), result.baselineRejectedSyncSourceIds)
        assertEquals(SourceSyncBaselineStoreReasons.STALE_REVISION, result.baselineRejectionReason)
    }

    // ------------------------------------------------------------ 3. 可选依赖：没有存储时行为不变

    @Test
    fun without_a_baseline_store_the_service_behaves_like_w6_71() {
        val store = FakeStore()
        val result = runBlocking {
            AndroidSourceSyncService(
                store = store,
                deviceId = "device-android",
                snapshotIdFactory = { "snap-fixed" },
                clock = { 1_700_000_000_000L },
            ).importEnvelope(SourceSyncCodec.encode(envelope(record("sync-a"))))
        }

        assertEquals(1, result.appliedCount)
        assertFalse(result.baselineSaved)
        assertEquals(emptyList<String>(), result.baselineRejectedSyncSourceIds)
        assertNull(result.baselineRejectionReason)
        assertEquals(1, store.createCalls)
    }

    // ------------------------------------------------------------ 4. 多端点：如实汇报，一条线路都不删

    @Test
    fun secondary_endpoints_are_reported_but_never_dropped() {
        val store = FakeStore()
        val primary = SourceEndpoint(url = "http://nas-a:8096/emby", isPrimary = true, sortOrder = 0)
        val secondary = SourceEndpoint(url = "http://nas-a:8097/emby", isPrimary = false, sortOrder = 1)
        val multi = record("sync-a").copy(endpoints = listOf(primary, secondary))

        val result = runBlocking {
            AndroidSourceSyncService(store = store, deviceId = "device-android")
                .importEnvelope(SourceSyncCodec.encode(envelope(multi)))
        }

        assertNotNull(result.endpointSummary)
        val summary = result.endpointSummary!!
        assertEquals(1, summary.notImportedCount)
        assertEquals(1, summary.recordsWithAlternatives)
        assertEquals(0, summary.recordsWithoutEndpoint)
        assertEquals("http://nas-a:8097/emby", summary.items.single().url)
        // 本机线路全量保留：两条都在库里（与 Windows「只导主端点」不同，这里不丢）。
        assertEquals(2, store.snapshots.values.single().endpoints.size)
    }

    @Test
    fun single_endpoint_envelopes_report_nothing() {
        val result = runBlocking {
            AndroidSourceSyncService(store = FakeStore(), deviceId = "device-android")
                .importEnvelope(SourceSyncCodec.encode(envelope(record("sync-a"))))
        }

        assertEquals(0, result.endpointSummary?.notImportedCount)
        assertEquals(0, result.endpointSummary?.recordsWithAlternatives)
        assertTrue(result.endpointSummary?.isEmpty == true)
    }

    // ------------------------------------------------------------ 夹具

    private fun service(
        store: FakeStore,
        baselineStore: ISourceSyncBaselineStore,
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

    private fun envelope(vararg records: SourceRecord): SourceSyncEnvelope = SourceSyncEnvelope(
        deviceId = "device-windows",
        snapshotId = "snap-windows",
        createdAtEpochMs = 1_700_000_000_000L,
        capabilities = SyncCapabilities(canSendSecrets = false, sourceTypes = emptyList()),
        sources = records.toList(),
        deletions = emptyList(),
    )

    /** 只需要 createLocalSource 的真实语义（返回新 id 并入表），其余方法不会被基线路径碰到。 */
    private class FakeStore : SourceSyncStore {
        val snapshots = LinkedHashMap<String, SourceSyncSourceSnapshot>()
        var createCalls = 0
        private var sequence = 0

        override suspend fun listSourceSnapshots(): List<SourceSyncSourceSnapshot> = snapshots.values.toList()

        override suspend fun createLocalSource(snapshot: SourceSyncSourceSnapshot): String {
            createCalls++
            val id = "srv-" + (++sequence)
            snapshots[id] = snapshot.copy(serverId = id)
            return id
        }

        override suspend fun updateLocalSource(serverId: String, snapshot: SourceSyncSourceSnapshot): Boolean =
            snapshots.put(serverId, snapshot.copy(serverId = serverId)) != null

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

        override fun observeServers(): kotlinx.coroutines.flow.Flow<List<com.mediahub.model.MediaServer>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun getServer(id: String): com.mediahub.model.MediaServer? = null
    }
}
