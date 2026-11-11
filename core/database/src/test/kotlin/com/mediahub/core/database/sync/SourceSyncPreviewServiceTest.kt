package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncPlanner
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SourceSyncSummary
import com.mediahub.core.common.sync.SourceTombstone
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.core.database.repository.SyncSourceStore
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
 * W6-B：来源同步预演服务（不依赖 UI / Android Context，纯 JVM + 内存 fake）。
 *
 * 三条要钉住的性质：
 * 1. 三桶（apply / requiresApproval / ignored）与 Windows CLI plan 的 counts 同义，可跨端对照；
 * 2. 预演**只读**：fake 上任何写方法被调用都会计数并在断言里暴露；
 * 3. 判定完全复用 SourceSyncPlanner，不在服务里另写一套规则。
 */
class SourceSyncPreviewServiceTest {

    private class FakeSyncSourceStore(
        private val records: List<LocalSourceRecord> = emptyList(),
    ) : SyncSourceStore {

        var listCalls = 0
            private set
        var writeAttempts = 0
            private set

        override fun observeServers(): Flow<List<MediaServer>> = flowOf(emptyList())

        override suspend fun getServer(id: String): MediaServer? = null

        override suspend fun listLocalSources(): List<LocalSourceRecord> {
            listCalls++
            return records
        }

        override suspend fun findBySyncSourceId(syncSourceId: String): LocalSourceRecord? =
            records.firstOrNull { it.syncSourceId == syncSourceId }

        // 预演不得触达的写路径：一旦被调用就计数（测试断言为 0）
        override suspend fun updateServer(server: MediaServer) {
            writeAttempts++
        }

        override suspend fun setDefault(id: String) {
            writeAttempts++
        }

        override suspend fun updateEndpointQuality(
            serverId: String,
            endpointId: String,
            expectedUrl: String,
            apiLatencyMs: Long?,
            mediaFirstByteMs: Long?,
            throughputMbps: Double?,
            protocol: String?,
            supportsRange: Boolean?,
            httpCode: Int?,
        ) {
            writeAttempts++
        }
    }

    private fun record(
        id: String,
        wire: String,
        url: String,
        revision: Long,
        scope: String? = null,
        name: String = "源",
    ) = SourceRecord(
        syncSourceId = id,
        sourceType = SyncSourceTypeValue.parse(wire),
        displayName = name,
        accountScope = scope,
        endpoints = listOf(SourceEndpoint(url = url, isPrimary = true)),
        recordRevision = revision,
    )

    /** 2 个可创建来源 + 1 个本端不认识的协议 + 1 个墓碑（与 task-29 的黄金样本同形状）。 */
    private fun mixedEnvelope(): SourceSyncEnvelope = SourceSyncEnvelope(
        deviceId = "device-a",
        snapshotId = "snap-1",
        createdAtEpochMs = 1_700_000_000_000,
        capabilities = SyncCapabilities(
            canSendSecrets = false,
            sourceTypes = listOf("emby", "jellyfin", "quantum-nas"),
        ),
        sources = listOf(
            record("local-emby", "emby", "http://nas-a:8096/emby/", 3, "alice", "客厅 Emby"),
            record("local-jelly", "jellyfin", "http://nas-a:8096/jelly", 1, "bob", "Jellyfin 楼上"),
            record("local-quantum", "quantum-nas", "https://quantum.example:9443/v1", 2, null, "Quantum NAS"),
        ),
        deletions = listOf(SourceTombstone("local-dav", 1_760_000_000_000, 5)),
    )

    @Test
    fun `preview of an empty store yields the apply approval ignored triple`() = runBlocking {
        val service = SourceSyncPreviewService(FakeSyncSourceStore())

        val preview = service.preview(SourceSyncCodec.encode(mixedEnvelope()))

        assertTrue("应返回 Ready，实际 " + preview, preview is SourceSyncPreview.Ready)
        val ready = preview as SourceSyncPreview.Ready
        assertEquals(SourceSyncBuckets(apply = 2, requiresApproval = 1, ignored = 1), ready.buckets)
        assertEquals(2, ready.summary.create)
        assertEquals(0, ready.summary.update)
        assertEquals(1, ready.summary.unsupported)
        assertEquals(1, ready.summary.unchanged)
        assertEquals(4, ready.summary.total)
        assertTrue(ready.hasChanges)
        assertEquals(listOf("local-emby", "local-jelly"), ready.toApply.map { it.syncSourceId })
        assertEquals(listOf("local-quantum"), ready.needsApproval.map { it.syncSourceId })
        assertEquals(listOf("local-dav"), ready.ignoredItems.map { it.syncSourceId })
    }

    @Test
    fun `malformed envelope json is rejected without reading the store`() = runBlocking {
        val store = FakeSyncSourceStore()
        val service = SourceSyncPreviewService(store)

        val malformed = service.preview("{")
        assertTrue(malformed is SourceSyncPreview.Rejected)
        assertEquals("malformed-json", (malformed as SourceSyncPreview.Rejected).reason)

        val missing = service.preview("{}")
        assertTrue(missing is SourceSyncPreview.Rejected)
        assertEquals("missing-field:schemaVersion", (missing as SourceSyncPreview.Rejected).reason)
        // 读也一次都没发生：解析失败就不该碰本机来源
        assertEquals(0, store.listCalls)
    }

    @Test
    fun `future schema version is rejected by the contract`() = runBlocking {
        val json = SourceSyncCodec.encode(mixedEnvelope()).replaceFirst(
            "\"schemaVersion\":1",
            "\"schemaVersion\":2",
        )

        val preview = SourceSyncPreviewService(FakeSyncSourceStore()).preview(json)

        assertTrue(preview is SourceSyncPreview.Rejected)
        assertEquals("unsupported-schema-version:2", (preview as SourceSyncPreview.Rejected).reason)
    }

    @Test
    fun `preview performs reads only and never writes`() = runBlocking {
        val store = FakeSyncSourceStore()

        SourceSyncPreviewService(store).preview(SourceSyncCodec.encode(mixedEnvelope()))

        assertEquals(1, store.listCalls)
        assertEquals("预演不得调用任何写方法", 0, store.writeAttempts)
    }

    @Test
    fun `preview is idempotent for the same envelope and store`() = runBlocking {
        val service = SourceSyncPreviewService(FakeSyncSourceStore())
        val encoded = SourceSyncCodec.encode(mixedEnvelope())

        val first = service.preview(encoded) as SourceSyncPreview.Ready
        val second = service.preview(encoded) as SourceSyncPreview.Ready

        assertEquals(first.buckets, second.buckets)
        assertEquals(
            first.plan.items.map { it.action to it.reason },
            second.plan.items.map { it.action to it.reason },
        )
        assertEquals(first.summary, second.summary)
    }

    @Test
    fun `preview delegates every decision to the planner contract`() = runBlocking {
        val envelope = mixedEnvelope()
        val locals = emptyList<LocalSourceRecord>()

        val preview = SourceSyncPreviewService(FakeSyncSourceStore(locals)).previewOf(envelope)
        val plan = SourceSyncPlanner.plan(envelope, locals)

        assertEquals(plan.items.size, preview.plan.items.size)
        plan.items.forEachIndexed { index, expected ->
            assertEquals(expected.action, preview.plan.items[index].action)
            assertEquals(expected.reason, preview.plan.items[index].reason)
            assertEquals(expected.requiresUserApproval, preview.plan.items[index].requiresUserApproval)
        }
        assertEquals(SourceSyncSummary.fromPlan(plan), preview.summary)
    }

    @Test
    fun `ready exposes the sending device and snapshot identity`() = runBlocking {
        val ready = SourceSyncPreviewService(FakeSyncSourceStore())
            .preview(SourceSyncCodec.encode(mixedEnvelope())) as SourceSyncPreview.Ready

        assertEquals("device-a", ready.deviceId)
        assertEquals("snap-1", ready.snapshotId)
    }

    @Test
    fun `local source with same revision but different content becomes a conflict`() = runBlocking {
        val locals = listOf(
            LocalSourceRecord(
                serverId = "srv-1",
                syncSourceId = "local-emby",
                type = ServerType.EMBY,
                url = "http://nas-a:8096/emby",
                accountScope = "alice",
                displayName = "旧名字", // 与远端 displayName 不同 -> same-revision-different-content
                recordRevision = 3L,
            ),
        )

        val ready = SourceSyncPreviewService(FakeSyncSourceStore(locals))
            .preview(SourceSyncCodec.encode(mixedEnvelope())) as SourceSyncPreview.Ready

        val emby = ready.plan.items.first { it.syncSourceId == "local-emby" }
        assertEquals(SourceSyncAction.CONFLICT, emby.action)
        assertEquals(SourceSyncReasons.SAME_REVISION_DIFFERENT_CONTENT, emby.reason)
        assertTrue(emby.requiresUserApproval)
        assertEquals("srv-1", emby.serverId)
        // 三桶：jellyfin 新建 + quantum 不支持 + dav 墓碑无本地
        assertEquals(SourceSyncBuckets(apply = 1, requiresApproval = 2, ignored = 1), ready.buckets)
        assertFalse(ready.ignoredItems.any { it.syncSourceId == "local-emby" })
    }

    @Test
    fun `local sources with matching identity and higher revision are no change`() = runBlocking {
        val locals = listOf(
            LocalSourceRecord(
                serverId = "srv-1",
                syncSourceId = "local-emby",
                type = ServerType.EMBY,
                url = "http://NAS-A:8096/emby/", // 归一化后与远端一致
                accountScope = "alice",
                displayName = "客厅 Emby",
                recordRevision = 9L, // 比远端 3 更新
            ),
        )

        val ready = SourceSyncPreviewService(FakeSyncSourceStore(locals))
            .preview(SourceSyncCodec.encode(mixedEnvelope())) as SourceSyncPreview.Ready

        val emby = ready.plan.items.first { it.syncSourceId == "local-emby" }
        assertEquals(SourceSyncAction.IGNORE_STALE, emby.action)
        assertEquals(SourceSyncReasons.STALE_REVISION, emby.reason)
        // ignored = 已同步 1 + 墓碑无本地 1
        assertEquals(SourceSyncBuckets(apply = 1, requiresApproval = 1, ignored = 2), ready.buckets)
    }
}
