package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.AndroidSourceTypeMap
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncFingerprints
import com.mediahub.core.common.sync.SourceSyncLocalIds
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SourceTombstone
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-C：Android 端来源同步的可用路径（导入应用 + 导出信封），纯 JVM + 内存 fake。
 *
 * 测试用同一份 Windows 真产物（core:common 与 core:database 各自 test/resources/sync/windows-envelope-device-a.json），
 * 因此四个桶的期望值可以直接和 Windows CLI plan 的 counts 对照：
 * apply=2 / requiresApproval=1（其中 unsupported 1）/ ignored=1。
 *
 * 钉住的性质：
 * 1. 只有 applied 桶写库；未知类型、墓碑、冲突一律不写；
 * 2. 墓碑**不删**本地来源（本接口根本没有删除方法）；
 * 3. 同信封二次导入零写入（内容指纹 + 修订号），且导出→再导入自己是 NoChange；
 * 4. 导出保真：未知 wire 原样、deviceId 稳定、snapshotId 每次唯一、补号只补一次。
 */
class AndroidSourceSyncServiceTest {

    // ------------------------------------------------------------ 被测对象

    private fun service(
        store: FakeSourceSyncStore,
        deviceId: String = "device-android",
    ): AndroidSourceSyncService = AndroidSourceSyncService(
        store = store,
        deviceId = deviceId,
        snapshotIdFactory = { "snap-fixed" },
        clock = { 1_700_000_000_000L },
    )

    // ------------------------------------------------------------ 夹具

    private fun goldenJson(): String =
        javaClass.getResourceAsStream("/sync/windows-envelope-device-a.json")!!
            .use { it.readBytes().decodeToString() }

    private fun snapshot(
        serverId: String,
        name: String,
        type: ServerType?,
        sourceTypeRaw: String? = null,
        syncSourceId: String? = null,
        recordRevision: Long = 0L,
        accountScope: String? = null,
        url: String = "http://nas-a:8096/emby",
    ): SourceSyncSourceSnapshot = SourceSyncSourceSnapshot(
        serverId = serverId,
        name = name,
        serverType = type,
        sourceTypeRaw = sourceTypeRaw,
        accountScope = accountScope,
        syncSourceId = syncSourceId,
        recordRevision = recordRevision,
        endpoints = listOf(SourceEndpoint(url = url, isPrimary = true, enabled = true, sortOrder = 0)),
        createdAtEpochMs = 1_600_000_000_000L,
    )

    private fun record(
        id: String,
        wire: String,
        name: String = "来源 " + id,
        scope: String? = null,
        revision: Long = 1L,
        url: String? = "http://nas-a:8096/emby",
    ): SourceRecord = SourceRecord(
        syncSourceId = id,
        sourceType = SyncSourceTypeValue.parse(wire),
        displayName = name,
        accountScope = scope,
        endpoints = if (url == null) {
            emptyList()
        } else {
            listOf(SourceEndpoint(url = url, isPrimary = true, enabled = true, sortOrder = 0))
        },
        recordRevision = revision,
    )

    private fun envelope(
        vararg records: SourceRecord,
        deletions: List<SourceTombstone> = emptyList(),
    ): SourceSyncEnvelope = SourceSyncEnvelope(
        deviceId = "device-windows",
        snapshotId = "snap-windows",
        createdAtEpochMs = 1_700_000_000_000L,
        capabilities = SyncCapabilities(canSendSecrets = false, sourceTypes = emptyList()),
        sources = records.toList(),
        deletions = deletions,
    )

    // ------------------------------------------------------------ 导入：黄金样本对齐

    @Test
    fun golden_envelope_lands_in_the_four_buckets_like_the_windows_cli() {
        val store = FakeSourceSyncStore()
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }

        assertFalse(result.isRejected)
        assertEquals(2, result.appliedCount)
        assertEquals(1, result.requiresApprovalCount)
        assertEquals(1, result.unsupportedCount)
        assertEquals(1, result.ignoredCount)
        assertEquals(0, result.failedCount)
        // 3 条来源 + 1 条墓碑；四桶之和必须等于计划条目数（unsupported 是 requiresApproval 的子集）
        assertEquals(4, result.totalCount)
        assertEquals(2, store.createCalls)
        assertEquals(0, store.updateCalls)
    }

    @Test
    fun applied_records_use_the_contract_reason_of_new_source() {
        val store = FakeSourceSyncStore()
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }

        assertEquals(
            listOf(SourceSyncReasons.NEW_SOURCE, SourceSyncReasons.NEW_SOURCE),
            result.applied.map { it.candidate.reason },
        )
        assertEquals(
            listOf(SourceSyncAction.CREATE, SourceSyncAction.CREATE),
            result.applied.map { it.candidate.action },
        )
    }

    @Test
    fun import_writes_normalized_endpoints_and_the_identity_columns() {
        val store = FakeSourceSyncStore()
        runBlocking { service(store).importEnvelope(goldenJson()) }

        val emby = store.snapshots.getValue("srv-new-1")
        assertEquals("客厅 Emby", emby.name)
        assertEquals(ServerType.EMBY, emby.serverType)
        assertEquals("emby", emby.sourceTypeRaw)
        assertEquals("alice", emby.accountScope)
        assertEquals("local-385c7c48494fcb81", emby.syncSourceId)
        assertEquals(3L, emby.recordRevision)
        // 端点按 D4 规范化后落库（默认端口显式、无尾斜杠）
        assertEquals("http://nas-a:8096/emby", emby.endpoints.single().url)
        assertTrue(emby.endpoints.single().isPrimary)
        assertTrue(emby.endpoints.single().enabled)
    }

    @Test
    fun unknown_wire_type_is_never_written_but_keeps_its_raw_value() {
        val store = FakeSourceSyncStore()
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }

        assertEquals(2, store.createCalls)
        assertTrue(store.snapshots.values.none { it.sourceTypeRaw == "quantum-nas" })
        val unsupported = result.unsupported.single()
        assertEquals(SourceSyncReasons.UNKNOWN_SOURCE_TYPE, unsupported.reason)
        assertEquals("quantum-nas", unsupported.sourceType)
        assertFalse(unsupported.isKnownSourceType)
    }

    @Test
    fun tombstone_pointing_nowhere_never_deletes_a_local_source() {
        val store = FakeSourceSyncStore()
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }

        assertEquals(0, store.updateCalls)
        assertEquals(2, store.snapshots.size)
        val tombstone = result.ignored.single()
        assertEquals(SourceSyncReasons.NO_LOCAL_SOURCE, tombstone.reason)
        assertEquals(SourceSyncAction.NO_CHANGE, tombstone.action)
    }

    @Test
    fun importing_the_same_envelope_twice_writes_nothing() {
        val store = FakeSourceSyncStore()
        val sync = service(store)
        runBlocking { sync.importEnvelope(goldenJson()) }
        val second = runBlocking { sync.importEnvelope(goldenJson()) }

        assertEquals(0, second.appliedCount)
        assertEquals(1, second.requiresApprovalCount)
        assertEquals(3, second.ignoredCount)
        assertEquals(0, second.failedCount)
        assertEquals(2, store.createCalls)
        assertEquals(0, store.updateCalls)
        assertEquals(
            listOf(SourceSyncReasons.UP_TO_DATE, SourceSyncReasons.UP_TO_DATE, SourceSyncReasons.NO_LOCAL_SOURCE),
            second.ignored.map { it.reason },
        )
    }

    @Test
    fun result_echoes_envelope_identity_and_content_hash() {
        val store = FakeSourceSyncStore()
        val json = goldenJson()
        val result = runBlocking { service(store).importEnvelope(json) }

        assertEquals("device-a", result.deviceId)
        assertEquals("1b96e7eb417e4caeb27eb60f3d1acfc3", result.snapshotId)
        assertEquals(SourceSyncFingerprints.content(SourceSyncCodec.decode(json)), result.envelopeContentHash)
    }

    // ------------------------------------------------------------ 导入：冲突与修订号

    @Test
    fun same_revision_with_different_content_asks_for_approval_instead_of_overwriting() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot(
                    serverId = "srv-emby",
                    name = "客厅 Emby（旧名）",
                    type = ServerType.EMBY,
                    sourceTypeRaw = "emby",
                    syncSourceId = "local-385c7c48494fcb81",
                    recordRevision = 3L,
                    accountScope = "alice",
                ),
            ),
        )
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }

        val conflict = result.requiresApproval.first { it.syncSourceId == "local-385c7c48494fcb81" }
        assertEquals(SourceSyncAction.CONFLICT, conflict.action)
        assertEquals(SourceSyncReasons.SAME_REVISION_DIFFERENT_CONTENT, conflict.reason)
        assertTrue(conflict.requiresUserApproval)
        assertEquals(0, store.updateCalls)
        assertEquals("客厅 Emby（旧名）", store.snapshots.getValue("srv-emby").name)
    }

    @Test
    fun higher_remote_revision_updates_the_local_source() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot(
                    serverId = "srv-emby",
                    name = "旧名",
                    type = ServerType.EMBY,
                    sourceTypeRaw = "emby",
                    syncSourceId = "local-385c7c48494fcb81",
                    recordRevision = 1L,
                    accountScope = "alice",
                ),
            ),
        )
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }

        assertEquals(1, store.updateCalls)
        val applied = result.applied.first { it.candidate.syncSourceId == "local-385c7c48494fcb81" }
        assertEquals(SourceSyncAction.UPDATE, applied.candidate.action)
        assertEquals(SourceSyncReasons.HIGHER_REVISION, applied.candidate.reason)
        assertEquals("srv-emby", applied.serverId)
        val updated = store.snapshots.getValue("srv-emby")
        assertEquals("客厅 Emby", updated.name)
        assertEquals(3L, updated.recordRevision)
    }

    @Test
    fun lower_remote_revision_is_ignored_as_stale() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot(
                    serverId = "srv-emby",
                    name = "更新过的名字",
                    type = ServerType.EMBY,
                    sourceTypeRaw = "emby",
                    syncSourceId = "local-385c7c48494fcb81",
                    recordRevision = 9L,
                    accountScope = "alice",
                ),
            ),
        )
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }

        val stale = result.ignored.first { it.syncSourceId == "local-385c7c48494fcb81" }
        assertEquals(SourceSyncAction.IGNORE_STALE, stale.action)
        assertEquals(SourceSyncReasons.STALE_REVISION, stale.reason)
        assertEquals(0, store.updateCalls)
        assertEquals("更新过的名字", store.snapshots.getValue("srv-emby").name)
    }

    @Test
    fun pre_w6_source_without_sync_id_is_only_adopted_after_approval() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot(
                    serverId = "srv-legacy",
                    name = "客厅 Emby",
                    type = ServerType.EMBY,
                    syncSourceId = null,
                    recordRevision = 7L,
                    accountScope = "alice",
                    url = "http://NAS-A:8096/emby/",
                ),
            ),
        )
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }

        val adopt = result.requiresApproval.first { it.syncSourceId == "local-385c7c48494fcb81" }
        assertEquals(SourceSyncAction.ADOPT, adopt.action)
        assertEquals(SourceSyncReasons.LEGACY_IDENTITY_MATCH, adopt.reason)
        assertTrue(adopt.requiresUserApproval)
        // 同一份信封里还有一条本机没有的 jellyfin 来源（正常新建），
        // 所以这里钉的是「legacy 行没被偷偷改写」，不是「整次导入零写入」。
        assertEquals(0, store.updateCalls)
        assertEquals(1, store.createCalls)
        assertEquals(2, store.snapshots.size)
        assertEquals("local-0a87ea9520c51a80", store.snapshots.getValue("srv-new-1").syncSourceId)
        assertNull(store.snapshots.getValue("srv-legacy").syncSourceId)
    }

    @Test
    fun tombstone_for_an_existing_source_asks_for_approval_and_keeps_the_row() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot(
                    serverId = "srv-old",
                    name = "要删的 WebDAV",
                    type = ServerType.WEBDAV,
                    sourceTypeRaw = "webdav",
                    syncSourceId = "local-f7ebca3dbf01eb17",
                    recordRevision = 3L,
                    url = "https://dav.example/gate",
                ),
            ),
        )
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }

        val delete = result.requiresApproval.first { it.syncSourceId == "local-f7ebca3dbf01eb17" }
        assertEquals(SourceSyncAction.DELETE, delete.action)
        assertEquals(SourceSyncReasons.TOMBSTONE, delete.reason)
        // 墓碑只提议，不动本地行
        assertTrue(store.snapshots.containsKey("srv-old"))
    }

    @Test
    fun stale_tombstone_is_ignored() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot(
                    serverId = "srv-old",
                    name = "已复活",
                    type = ServerType.WEBDAV,
                    sourceTypeRaw = "webdav",
                    syncSourceId = "local-f7ebca3dbf01eb17",
                    recordRevision = 9L,
                    url = "https://dav.example/gate",
                ),
            ),
        )
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }

        val stale = result.ignored.first { it.syncSourceId == "local-f7ebca3dbf01eb17" }
        assertEquals(SourceSyncAction.IGNORE_STALE, stale.action)
        assertEquals(SourceSyncReasons.STALE_REVISION, stale.reason)
        assertTrue(store.snapshots.containsKey("srv-old"))
    }

    // ------------------------------------------------------------ 导入：拒绝与失败

    @Test
    fun malformed_json_is_rejected_without_touching_the_store() {
        val store = FakeSourceSyncStore()
        val result = runBlocking { service(store).importEnvelope("{") }

        assertTrue(result.isRejected)
        assertEquals("malformed-json", result.rejectedReason)
        assertEquals(0, result.totalCount)
        assertEquals(0, store.listCalls)
        assertEquals(0, store.createCalls)
    }

    @Test
    fun future_schema_version_is_rejected_before_any_write() {
        val store = FakeSourceSyncStore()
        val bumped = goldenJson().replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")
        val result = runBlocking { service(store).importEnvelope(bumped) }

        assertTrue(result.isRejected)
        assertEquals("unsupported-schema-version:2", result.rejectedReason)
        assertEquals(0, store.createCalls)
    }

    @Test
    fun create_without_usable_endpoint_asks_for_approval_and_writes_nothing() {
        val store = FakeSourceSyncStore()
        val json = SourceSyncCodec.encode(
            envelope(record(id = "local-new", wire = "emby", name = "无地址 Emby", url = null)),
        )
        val result = runBlocking { service(store).importEnvelope(json) }

        val candidate = result.requiresApproval.single()
        assertEquals(SourceSyncAction.CREATE, candidate.action)
        assertEquals(SourceSyncReasons.NO_USABLE_ENDPOINT, candidate.reason)
        assertTrue(candidate.requiresUserApproval)
        assertEquals(0, store.createCalls)
        assertEquals(0, result.failedCount)
    }

    @Test
    fun update_whose_endpoints_cannot_be_normalized_is_reported_as_failed() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot(
                    serverId = "srv-emby",
                    name = "客厅 Emby",
                    type = ServerType.EMBY,
                    sourceTypeRaw = "emby",
                    syncSourceId = "local-emby",
                    recordRevision = 1L,
                ),
            ),
        )
        val json = SourceSyncCodec.encode(
            envelope(record(id = "local-emby", wire = "emby", name = "客厅 Emby", revision = 3L, url = "ftp://nas:21/emby")),
        )
        val result = runBlocking { service(store).importEnvelope(json) }

        assertEquals(0, result.appliedCount)
        assertEquals(1, result.failedCount)
        assertEquals(SourceSyncReasons.NO_USABLE_ENDPOINT, result.failed.single().reason)
        assertEquals(0, store.updateCalls)
        // 失败条目不能被同时算进 requiresApproval（否则四桶之和会超过计划条目数）
        assertEquals(0, result.requiresApprovalCount)
        assertEquals(1, result.totalCount)
    }

    @Test
    fun local_source_that_vanished_before_write_is_reported_not_silently_dropped() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot(
                    serverId = "srv-emby",
                    name = "客厅 Emby",
                    type = ServerType.EMBY,
                    sourceTypeRaw = "emby",
                    syncSourceId = "local-emby",
                    recordRevision = 1L,
                ),
            ),
        )
        store.rejectUpdates = true
        val json = SourceSyncCodec.encode(
            envelope(record(id = "local-emby", wire = "emby", name = "客厅 Emby", revision = 3L)),
        )
        val result = runBlocking { service(store).importEnvelope(json) }

        assertEquals(0, result.appliedCount)
        assertEquals(AndroidSourceSyncService.SOURCE_VANISHED, result.failed.single().reason)
        assertEquals(1, result.failedCount)
    }

    @Test
    fun four_buckets_stay_disjoint_and_add_up_when_all_of_them_are_non_empty() {
        // 一份信封里同时踩到四条路径：新建（applied）、同修订号不同内容（requiresApproval）、
        // 未知 wire 类型（unsupported，requiresApproval 的子集）、过时墓碑（ignored），
        // 以及规划可写但写入被拒（failed = Android 独有的运营桶）。
        // 钉住的不变量：四桶两两互斥、相加等于计划条目数、unsupported 不重复计数。
        val store = FakeSourceSyncStore(
            listOf(
                snapshot("srv-a", "甲", ServerType.EMBY, "emby", syncSourceId = "local-a", recordRevision = 1L),
                snapshot("srv-b", "乙", ServerType.JELLYFIN, "jellyfin", syncSourceId = "local-b", recordRevision = 1L),
            ),
        )
        store.rejectUpdates = true
        val json = SourceSyncCodec.encode(
            envelope(
                record(id = "local-new", wire = "emby", name = "新的", revision = 1L, scope = "alice"),
                record(id = "local-a", wire = "emby", name = "改了名字", revision = 1L),
                record(id = "local-b", wire = "jellyfin", name = "乙", revision = 3L),
                record(id = "local-q", wire = "quantum-nas", name = "量子 NAS", revision = 1L),
                deletions = listOf(SourceTombstone("local-gone", 1_700_000_000_000L, 1L)),
            ),
        )
        val result = runBlocking { service(store).importEnvelope(json) }

        assertEquals(5, result.totalCount)
        assertEquals(1, result.appliedCount)
        assertEquals(2, result.requiresApprovalCount)
        assertEquals(1, result.unsupportedCount)
        assertEquals(1, result.ignoredCount)
        assertEquals(1, result.failedCount)
        assertEquals(
            5,
            result.appliedCount + result.requiresApprovalCount + result.ignoredCount + result.failedCount,
        )

        val seen = LinkedHashSet<String>()
        val bucketIds: List<Pair<String, List<String>>> = listOf(
            "applied" to result.applied.map { it.candidate.syncSourceId ?: "<null>" },
            "requiresApproval" to result.requiresApproval.map { it.syncSourceId ?: "<null>" },
            "ignored" to result.ignored.map { it.syncSourceId ?: "<null>" },
            "failed" to result.failed.map { it.syncSourceId ?: "<null>" },
        )
        bucketIds.forEach { (bucket, ids) ->
            ids.forEach { id -> assertTrue("$id 同时出现在 $bucket 与其它桶里", seen.add(id)) }
        }
        assertEquals(5, seen.size)

        // 写入只发生在 applied 桶：新建 1 条、更新被拒 1 次、快照总数 2 旧 + 1 新
        assertEquals(1, store.createCalls)
        assertEquals(1, store.updateCalls)
        assertEquals(3, store.snapshots.size)
    }

    // ------------------------------------------------------------ 导出

    @Test
    fun export_is_stable_for_device_but_unique_for_snapshot() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot("srv-1", "甲", ServerType.EMBY, "emby", syncSourceId = "local-bbb"),
                snapshot("srv-2", "乙", ServerType.JELLYFIN, "jellyfin", syncSourceId = "local-aaa", url = "http://nas-a:8096/jelly"),
            ),
        )
        var counter = 0
        val sync = AndroidSourceSyncService(
            store = store,
            deviceId = "device-android",
            snapshotIdFactory = { "snap-" + (++counter) },
            clock = { 1_700_000_000_000L },
        )
        val first = runBlocking { sync.export() }
        val second = runBlocking { sync.export() }

        assertEquals("device-android", first.envelope.deviceId)
        assertEquals("device-android", second.envelope.deviceId)
        assertNotEquals(first.envelope.snapshotId, second.envelope.snapshotId)
        // 记录按 syncSourceId 稳定排序：local-aaa 在 local-bbb 前面
        assertEquals(listOf("local-aaa", "local-bbb"), first.envelope.sources.map { it.syncSourceId })
        assertTrue(first.derivedIdentityServerIds.isEmpty())
    }

    @Test
    fun export_preserves_unknown_raw_source_type_and_declares_it_after_known_ones() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot("srv-1", "客厅 Emby", ServerType.EMBY, "emby", syncSourceId = "local-1"),
                snapshot("srv-2", "Quantum NAS", null, "quantum-nas", syncSourceId = "local-2", url = "https://quantum.example:9443/v1"),
            ),
        )
        val export = runBlocking { service(store).export() }

        val quantum = export.envelope.sources.first { it.syncSourceId == "local-2" }
        assertEquals("quantum-nas", quantum.sourceType.raw)
        assertTrue(quantum.sourceType.isKnown == false)
        assertEquals(listOf("emby", "quantum-nas"), export.envelope.capabilities.sourceTypes)
        // 未知类型的记录必须原样进信封（不得被丢弃）
        assertEquals(2, export.envelope.sources.size)
        // 编码后的 JSON 能原样读回（不丢字段）
        assertEquals(export.envelope, SourceSyncCodec.decode(export.json))
    }

    @Test
    fun export_derives_a_stable_sync_id_only_once() {
        val store = FakeSourceSyncStore(listOf(snapshot("srv-a", "甲", ServerType.EMBY, "emby")))
        val sync = service(store)
        val first = runBlocking { sync.export() }

        val derived = SourceSyncLocalIds.derive("device-android", "srv-a")
        assertEquals(listOf("srv-a"), first.derivedIdentityServerIds)
        assertEquals(1, store.bindCalls)
        assertEquals(derived, first.envelope.sources.single().syncSourceId)
        assertEquals(22, derived.length)
        assertTrue(derived.startsWith("local-"))

        val second = runBlocking { sync.export() }
        assertTrue(second.derivedIdentityServerIds.isEmpty())
        assertEquals(1, store.bindCalls)
        assertEquals(derived, second.envelope.sources.single().syncSourceId)
    }

    @Test
    fun export_skips_sources_without_endpoints() {
        val store = FakeSourceSyncStore(
            listOf(
                SourceSyncSourceSnapshot(
                    serverId = "srv-empty",
                    name = "没有线路",
                    serverType = ServerType.SMB,
                    sourceTypeRaw = "smb",
                    syncSourceId = "local-empty",
                    endpoints = emptyList(),
                ),
            ),
        )
        val export = runBlocking { service(store).export() }

        assertEquals(listOf("srv-empty"), export.skippedServerIds)
        assertTrue(export.envelope.sources.isEmpty())
    }

    @Test
    fun export_then_import_into_itself_is_a_no_op() {
        val store = FakeSourceSyncStore(
            listOf(
                snapshot("srv-1", "客厅 Emby", ServerType.EMBY, "emby", syncSourceId = "local-emby", recordRevision = 3L, accountScope = "alice"),
                snapshot("srv-2", "楼上 Jellyfin", ServerType.JELLYFIN, "jellyfin", recordRevision = 1L, url = "http://nas-a:8096/jelly"),
            ),
        )
        val sync = service(store)
        val exported = runBlocking { sync.export() }
        val result = runBlocking { sync.importEnvelope(exported.json) }

        assertEquals(0, result.appliedCount)
        assertEquals(0, result.requiresApprovalCount)
        assertEquals(0, result.failedCount)
        assertEquals(2, result.ignoredCount)
        assertEquals(
            listOf(SourceSyncReasons.UP_TO_DATE, SourceSyncReasons.UP_TO_DATE),
            result.ignored.map { it.reason },
        )
        assertEquals(0, store.updateCalls)
        assertEquals(0, store.createCalls)
    }

    @Test
    fun export_never_invents_tombstones() {
        val store = FakeSourceSyncStore(listOf(snapshot("srv-1", "甲", ServerType.EMBY, "emby", syncSourceId = "local-1")))
        val export = runBlocking { service(store).export() }

        assertTrue(export.envelope.deletions.isEmpty())
        assertFalse(export.envelope.capabilities.canSendSecrets)
    }

    // ------------------------------------------------------------ 内容指纹

    @Test
    fun fingerprint_ignores_revision_but_follows_identity_fields() {
        val base = record(id = "local-1", wire = "emby", name = "客厅 Emby", revision = 1L)
        val sameContentNewRevision = base.copy(recordRevision = 99L)
        val renamed = base.copy(displayName = "客厅 Emby 2")

        assertEquals(SourceSyncFingerprints.content(base), SourceSyncFingerprints.content(sameContentNewRevision))
        assertNotEquals(SourceSyncFingerprints.content(base), SourceSyncFingerprints.content(renamed))
        // of() 并入修订号，所以修订号变化会让它变
        assertNotEquals(SourceSyncFingerprints.of(base), SourceSyncFingerprints.of(sameContentNewRevision))
    }

    @Test
    fun fingerprint_ignores_endpoint_writing_details() {
        val tailSlash = record(id = "local-1", wire = "emby", url = "http://NAS-A:8096/emby/")
        val canonical = record(id = "local-1", wire = "emby", url = "http://nas-a:8096/emby")

        assertEquals(SourceSyncFingerprints.content(tailSlash), SourceSyncFingerprints.content(canonical))
    }

    // ------------------------------------------------------------ 内存 fake

    private class FakeSourceSyncStore(
        initial: List<SourceSyncSourceSnapshot> = emptyList(),
    ) : SourceSyncStore {

        val snapshots = LinkedHashMap<String, SourceSyncSourceSnapshot>()

        var listCalls = 0
            private set
        var createCalls = 0
            private set
        var updateCalls = 0
            private set
        var bindCalls = 0
            private set

        /** 模拟"规划之后、写入之前本地记录被删"（update 返回 false）。 */
        var rejectUpdates = false

        private var sequence = 0

        init {
            initial.forEach { snapshots[it.serverId] = it }
        }

        override suspend fun listSourceSnapshots(): List<SourceSyncSourceSnapshot> {
            listCalls++
            return snapshots.values.toList()
        }

        override suspend fun createLocalSource(snapshot: SourceSyncSourceSnapshot): String {
            createCalls++
            val id = "srv-new-" + (++sequence)
            snapshots[id] = snapshot.copy(serverId = id)
            return id
        }

        override suspend fun updateLocalSource(serverId: String, snapshot: SourceSyncSourceSnapshot): Boolean {
            updateCalls++
            if (rejectUpdates) return false
            if (!snapshots.containsKey(serverId)) return false
            snapshots[serverId] = snapshot.copy(serverId = serverId)
            return true
        }

        override suspend fun bindSyncIdentity(
            serverId: String,
            syncSourceId: String,
            recordRevision: Long,
            sourceTypeRaw: String?,
        ): Boolean {
            bindCalls++
            val existing = snapshots[serverId] ?: return false
            snapshots[serverId] = existing.copy(
                syncSourceId = syncSourceId,
                recordRevision = recordRevision,
                sourceTypeRaw = sourceTypeRaw ?: existing.sourceTypeRaw,
            )
            return true
        }

        override suspend fun listLocalSources(): List<LocalSourceRecord> =
            snapshots.values.map { it.toLocalRecord() }

        override suspend fun findBySyncSourceId(syncSourceId: String): LocalSourceRecord? =
            snapshots.values.firstOrNull { it.syncSourceId == syncSourceId }?.toLocalRecord()

        override fun observeServers(): Flow<List<MediaServer>> = flowOf(emptyList())

        override suspend fun getServer(id: String): MediaServer? = null

        private fun SourceSyncSourceSnapshot.toLocalRecord(): LocalSourceRecord {
            val resolvedType = sourceTypeRaw?.takeIf { it.isNotBlank() }
                ?.let { AndroidSourceTypeMap.toServerType(it) }
                ?: serverType
            val activeUrl = endpoints.firstOrNull { it.isPrimary && it.enabled }?.url
                ?: endpoints.firstOrNull { it.enabled }?.url
            return LocalSourceRecord(
                serverId = serverId,
                syncSourceId = syncSourceId,
                type = resolvedType,
                url = activeUrl,
                accountScope = accountScope,
                displayName = name,
                recordRevision = recordRevision,
                sourceTypeRaw = sourceTypeRaw,
            )
        }
    }
}
