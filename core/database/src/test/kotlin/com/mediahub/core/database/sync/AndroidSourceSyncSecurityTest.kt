package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.AndroidSourceTypeMap
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncEnvelope
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-J 安全回归：导入服务在恶意 / 残缺信封下的写库边界。
 *
 * 判据是"坏信封零写入"：每个失败或需审批的用例都断言 fake store 的
 * createLocalSource / updateLocalSource 调用数为 0，而不是只看 reason 文案。
 * W6J-1（同信封重复 syncSourceId 会被写两条、第二条撞唯一索引后异常整封逃逸）已修：
 * 服务在规划之前先做信封内去重并整封拒绝（stable code `duplicate-sync-source-id`），
 * 逐条写入也加了兜底 catch（`local-source-write-failed:<异常类名>`），异常不再逃出 importEnvelope。
 */
class AndroidSourceSyncSecurityTest {

    private val goldenDeviceId = "device-windows"

    private fun service(store: SecurityFakeStore): AndroidSourceSyncService = AndroidSourceSyncService(
        store = store,
        deviceId = "device-android",
        snapshotIdFactory = { "snap-fixed" },
        clock = { 1_700_000_000_000L },
    )

    // ------------------------------------------------------------ 原始 JSON 夹具

    private fun recordJson(
        id: String,
        wire: String = "emby",
        revision: Long = 1L,
        url: String = "http://nas-a:8096/emby",
    ): String = "{\"syncSourceId\":\"" + id + "\",\"sourceType\":\"" + wire + "\"," +
        "\"displayName\":\"来源 " + id + "\",\"endpoints\":[{\"url\":\"" + url + "\"," +
        "\"isPrimary\":true,\"enabled\":true}],\"recordRevision\":" + revision + "}"

    private fun envelopeJson(
        sources: String,
        deletions: String = "[]",
        schemaVersion: String = "1",
        deviceId: String = "\"" + goldenDeviceId + "\"",
    ): String = "{\"schemaVersion\":" + schemaVersion +
        ",\"minimumReaderVersion\":1" +
        ",\"deviceId\":" + deviceId +
        ",\"snapshotId\":\"snap-windows\"" +
        ",\"createdAtEpochMs\":1700000000000" +
        ",\"capabilities\":{}" +
        ",\"sources\":" + sources +
        ",\"deletions\":" + deletions + "}"

    // ------------------------------------------------------------ 解析失败：整封拒绝且不碰 store

    private fun assertRejectedWithoutStoreAccess(json: String, expectedReason: String) {
        val store = SecurityFakeStore()
        val result = runBlocking { service(store).importEnvelope(json) }
        assertTrue(result.isRejected)
        assertEquals(expectedReason, result.rejectedReason)
        assertEquals(0, result.appliedCount)
        assertEquals(0, result.requiresApprovalCount)
        assertEquals(0, result.ignoredCount)
        assertEquals(0, result.failedCount)
        assertEquals(0, result.totalCount)
        assertEquals("解码失败不得查询本地来源", 0, store.listCalls)
        assertEquals(0, store.createCalls)
        assertEquals(0, store.updateCalls)
    }

    @Test
    fun truncated_json_is_rejected_without_touching_the_store() {
        assertRejectedWithoutStoreAccess("{\"schemaVersion\":1,", "malformed-json")
    }

    @Test
    fun missing_schema_version_is_rejected_without_touching_the_store() {
        assertRejectedWithoutStoreAccess(
            "{\"minimumReaderVersion\":1,\"deviceId\":\"device-windows\",\"sources\":[]}",
            "missing-field:schemaVersion",
        )
    }

    @Test
    fun future_schema_version_is_rejected_without_touching_the_store() {
        assertRejectedWithoutStoreAccess(envelopeJson("[]", schemaVersion = "2"), "unsupported-schema-version:2")
    }

    @Test
    fun future_minimum_reader_version_is_rejected_without_touching_the_store() {
        val json = "{\"schemaVersion\":1,\"minimumReaderVersion\":2,\"deviceId\":\"device-windows\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1,\"capabilities\":{},\"sources\":[],\"deletions\":[]}"
        assertRejectedWithoutStoreAccess(json, "unsupported-minimum-reader-version:2")
    }

    @Test
    fun blank_device_id_is_rejected_without_touching_the_store() {
        assertRejectedWithoutStoreAccess(envelopeJson("[]", deviceId = "\"   \""), "missing-field:deviceId")
    }

    @Test
    fun sources_that_are_not_an_array_are_rejected() {
        assertRejectedWithoutStoreAccess(envelopeJson("{}"), "invalid-field:sources")
    }

    @Test
    fun source_elements_that_are_not_objects_are_rejected() {
        assertRejectedWithoutStoreAccess(envelopeJson("[1,2,3]"), "invalid-field:sources")
    }

    @Test
    fun blank_sync_source_id_is_rejected_before_any_write() {
        assertRejectedWithoutStoreAccess(envelopeJson("[" + recordJson("") + "]"), "missing-field:syncSourceId")
    }

    @Test
    fun blank_endpoint_url_is_rejected_before_any_write() {
        val json = "{\"syncSourceId\":\"local-1\",\"sourceType\":\"emby\",\"displayName\":\"x\"," +
            "\"endpoints\":[{\"url\":\"   \"}],\"recordRevision\":1}"
        assertRejectedWithoutStoreAccess(envelopeJson("[" + json + "]"), "missing-field:url")
    }

    // ------------------------------------------------------------ 需审批 / 忽略：零写入

    @Test
    fun all_blank_endpoints_require_approval_and_write_nothing() {
        val store = SecurityFakeStore()
        // 空白 url 在解码阶段就是 missing-field:url；"端点数组为空"才落到审批分支。
        val empty = "{\"syncSourceId\":\"local-1\",\"sourceType\":\"emby\",\"displayName\":\"x\"," +
            "\"endpoints\":[],\"recordRevision\":1}"
        val result = runBlocking { service(store).importEnvelope(envelopeJson("[" + empty + "]")) }
        assertFalse(result.isRejected)
        assertEquals(1, result.requiresApprovalCount)
        assertEquals(SourceSyncReasons.NO_USABLE_ENDPOINT, result.requiresApproval.single().reason)
        assertEquals(0, store.createCalls)
        assertEquals(0, store.updateCalls)
        assertTrue(store.snapshots.isEmpty())
    }

    @Test
    fun unusable_scheme_endpoints_require_approval_and_write_nothing() {
        val store = SecurityFakeStore()
        val result = runBlocking {
            service(store).importEnvelope(envelopeJson("[" + recordJson("local-1", url = "ftp://nas-a:21/emby") + "]"))
        }
        assertFalse(result.isRejected)
        assertEquals(1, result.requiresApprovalCount)
        assertEquals(SourceSyncReasons.NO_USABLE_ENDPOINT, result.requiresApproval.single().reason)
        assertEquals(0, store.createCalls)
    }

    @Test
    fun tombstone_for_an_unknown_local_source_is_ignored_and_writes_nothing() {
        val store = SecurityFakeStore()
        val tombstone = "{\"syncSourceId\":\"local-ghost\",\"deletedAtEpochMs\":1700000000000,\"recordRevision\":2}"
        val result = runBlocking { service(store).importEnvelope(envelopeJson("[]", deletions = "[" + tombstone + "]")) }
        assertEquals(1, result.ignoredCount)
        assertEquals(SourceSyncReasons.NO_LOCAL_SOURCE, result.ignored.single().reason)
        assertEquals(0, store.createCalls)
        assertEquals(0, store.updateCalls)
        assertTrue(store.snapshots.isEmpty())
    }

    @Test
    fun unknown_wire_type_does_not_touch_an_existing_bound_source() {
        val existing = snapshot("srv-1", "甲", ServerType.EMBY, "emby", syncSourceId = "local-known", recordRevision = 1L)
        val store = SecurityFakeStore(listOf(existing))
        // 修订号更高：若类型被识别，这会是一次 Update；未知类型必须连匹配都不做。
        val result = runBlocking {
            service(store).importEnvelope(envelopeJson("[" + recordJson("local-known", wire = "quantum-nas", revision = 9L) + "]"))
        }
        assertEquals(1, result.unsupportedCount)
        assertEquals(SourceSyncReasons.UNKNOWN_SOURCE_TYPE, result.unsupported.single().reason)
        assertEquals("quantum-nas", result.unsupported.single().sourceType)
        assertEquals(0, result.appliedCount)
        assertEquals(0, store.createCalls)
        assertEquals(0, store.updateCalls)
        val untouched = store.snapshots.getValue("srv-1")
        assertEquals("甲", untouched.name)
        assertEquals(1L, untouched.recordRevision)
        assertEquals("emby", untouched.sourceTypeRaw)
    }

    // ------------------------------------------------------------ 缺陷与凭据

    @Test
    fun duplicate_sync_ids_never_write_even_once() {
        // W6J-1 回归：同一信封里两条相同 syncSourceId 必须整封拒绝，且零写入。
        // 修复前：规划器只与 locals 比对，两条都判 CREATE → 第一条落库、第二条撞
        // index_servers_sync_source_id，SQLiteConstraintException 逃出 importEnvelope。
        val store = SecurityFakeStore()
        val sources = "[" + recordJson("local-dup") + "," + recordJson("local-dup", revision = 2L) + "]"
        val result = runBlocking { service(store).importEnvelope(envelopeJson(sources)) }
        assertTrue(result.isRejected)
        assertEquals(AndroidSourceSyncService.DUPLICATE_SYNC_SOURCE_ID, result.rejectedReason)
        assertTrue(result.rejectedMessage!!.contains("local-dup"))
        assertEquals(0, result.totalCount)
        assertEquals(0, result.appliedCount)
        assertEquals(0, result.failedCount)
        assertEquals("整封拒绝时连本地来源都不必查", 0, store.listCalls)
        assertEquals(0, store.createCalls)
        assertEquals(0, store.updateCalls)
        assertTrue(store.snapshots.isEmpty())
    }

    @Test
    fun duplicate_sync_ids_differing_only_by_case_or_padding_are_rejected_too() {
        // 身份两端都是"trim + 忽略大小写"，仅大小写/前后空格不同的两条指向同一个来源。
        val store = SecurityFakeStore()
        val sources = "[" + recordJson("local-dup") + "," + recordJson("  LOCAL-DUP  ") + "]"
        val result = runBlocking { service(store).importEnvelope(envelopeJson(sources)) }
        assertEquals(AndroidSourceSyncService.DUPLICATE_SYNC_SOURCE_ID, result.rejectedReason)
        assertEquals(0, store.createCalls)
        assertTrue(store.snapshots.isEmpty())
    }

    @Test
    fun duplicate_check_does_not_reject_a_healthy_envelope() {
        // 去重只能拒绝真重复：两条不同 id 的信封必须照常应用（防止去重键写错把正常信封也毙掉）。
        val store = SecurityFakeStore()
        val sources = "[" + recordJson("local-a") + "," + recordJson("local-b") + "]"
        val result = runBlocking { service(store).importEnvelope(envelopeJson(sources)) }
        assertFalse(result.isRejected)
        assertEquals(2, result.appliedCount)
        assertEquals(2, store.createCalls)
    }

    @Test
    fun write_failure_is_reported_as_a_failed_entry_instead_of_escaping() {
        // 存储层拒绝（唯一索引 / IO / 权限）必须变成 failed 桶里的稳定码，不能整封抛异常：
        // 抛出去调用方既不知道哪条失败，也已经有一条悄悄写进去了。
        val store = SecurityFakeStore()
        store.failCreateWith = IllegalStateException("disk is full")
        val result = runBlocking { service(store).importEnvelope(envelopeJson("[" + recordJson("local-1") + "]")) }
        assertFalse(result.isRejected)
        assertEquals(0, result.appliedCount)
        assertEquals(1, result.failedCount)
        assertTrue(
            "稳定码应带异常类名以便区分磁盘/索引，实际 " + result.failed.single().reason,
            result.failed.single().reason.startsWith(AndroidSourceSyncService.WRITE_FAILED + ":"),
        )
        assertEquals("local-source-write-failed:IllegalStateException", result.failed.single().reason)
        // 四桶不变式：applied + requiresApproval + ignored + failed == 计划条目数
        assertEquals(1, result.totalCount)
        assertTrue(store.snapshots.isEmpty())
    }

    @Test
    fun a_failed_write_does_not_hide_the_ones_that_did_get_written() {
        // 部分写入必须如实汇报：好的一条进 applied，坏的一条进 failed，两者都能被调用方看到。
        val store = SecurityFakeStore()
        val sources = "[" + recordJson("local-ok") + "," + recordJson("local-bad") + "]"
        store.failCreateFor = setOf("来源 local-bad")
        val result = runBlocking { service(store).importEnvelope(envelopeJson(sources)) }
        assertEquals(1, result.appliedCount)
        assertEquals(1, result.failedCount)
        assertEquals(2, store.createCalls)
        assertEquals(1, store.snapshots.size)
    }

    @Test
    fun rejected_envelope_does_not_echo_credentials() {
        val secret = "hunter2-super-secret"
        val store = SecurityFakeStore()
        val result = runBlocking {
            service(store).importEnvelope("{\"schemaVersion\":1,\"password\":\"" + secret + "\"")
        }
        assertTrue(result.isRejected)
        assertEquals("malformed-json", result.rejectedReason)
        assertEquals("The sync document is not valid JSON.", result.rejectedMessage)
        assertFalse(result.rejectedMessage!!.contains(secret))
        assertFalse(result.toString().contains(secret))
        assertEquals(0, store.listCalls)
    }

    @Test
    fun secrets_are_not_copied_into_results_or_storage() {
        val secret = "hunter2-super-secret"
        val store = SecurityFakeStore()
        val body = "{\"syncSourceId\":\"local-1\",\"sourceType\":\"quantum-nas\",\"displayName\":\"x\"," +
            "\"endpoints\":[{\"url\":\"http://nas-a:8096/emby\"}],\"recordRevision\":1," +
            "\"secrets\":{\"password\":\"" + secret + "\"}}"
        val result = runBlocking { service(store).importEnvelope(envelopeJson("[" + body + "]")) }
        assertEquals(1, result.unsupportedCount)
        assertFalse(result.toString().contains(secret))
        assertFalse(store.snapshots.toString().contains(secret))
        assertEquals(0, store.createCalls)
    }

    // ------------------------------------------------------------ 正向对照

    @Test
    fun golden_envelope_still_applies_two_sources() {
        val store = SecurityFakeStore()
        val result = runBlocking { service(store).importEnvelope(goldenJson()) }
        assertFalse(result.isRejected)
        assertEquals(2, result.appliedCount)
        assertEquals(1, result.requiresApprovalCount)
        assertEquals(1, result.ignoredCount)
        assertEquals(0, result.failedCount)
        assertEquals(2, store.createCalls)
        assertEquals(0, store.updateCalls)
    }

    @Test
    fun mixed_envelope_writes_only_the_known_sources() {
        val store = SecurityFakeStore()
        val sources = "[" + recordJson("local-ok") + "," + recordJson("local-unknown", wire = "quantum-nas") + "]"
        val result = runBlocking { service(store).importEnvelope(envelopeJson(sources)) }
        assertEquals(1, result.appliedCount)
        assertEquals(1, result.unsupportedCount)
        assertEquals(1, store.createCalls)
        assertEquals(listOf(SourceSyncReasons.NEW_SOURCE), result.applied.map { it.candidate.reason })
        assertEquals(1, store.snapshots.size)
    }

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
        url: String = "http://nas-a:8096/emby",
    ): SourceSyncSourceSnapshot = SourceSyncSourceSnapshot(
        serverId = serverId,
        name = name,
        serverType = type,
        sourceTypeRaw = sourceTypeRaw,
        accountScope = null,
        syncSourceId = syncSourceId,
        recordRevision = recordRevision,
        endpoints = listOf(SourceEndpoint(url = url, isPrimary = true, enabled = true, sortOrder = 0)),
        createdAtEpochMs = 1_600_000_000_000L,
    )

    private class SecurityFakeStore(
        initial: List<SourceSyncSourceSnapshot> = emptyList(),
    ) : SourceSyncStore {

        val snapshots = LinkedHashMap<String, SourceSyncSourceSnapshot>()

        var listCalls = 0
            private set
        var createCalls = 0
            private set
        var updateCalls = 0
            private set

        /** 注入写入故障：所有 createLocalSource 都抛这个异常（模拟唯一索引/磁盘/权限）。 */
        var failCreateWith: Exception? = null

        /** 按展示名注入故障：只有命中集合的那几条抛异常（模拟部分写入）。 */
        var failCreateFor: Set<String> = emptySet()

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
            failCreateWith?.let { throw it }
            if (failCreateFor.contains(snapshot.name)) throw IllegalStateException("rejected " + snapshot.name)
            val id = "srv-new-" + (++sequence)
            snapshots[id] = snapshot.copy(serverId = id)
            return id
        }

        override suspend fun updateLocalSource(serverId: String, snapshot: SourceSyncSourceSnapshot): Boolean {
            updateCalls++
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
