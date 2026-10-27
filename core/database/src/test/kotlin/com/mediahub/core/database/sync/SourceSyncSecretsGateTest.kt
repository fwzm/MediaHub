package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.InMemorySourceSyncBaselineStore
import com.mediahub.core.common.sync.ISourceSyncBaselineStore
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncSecrets
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncRoundReasons
import com.mediahub.core.common.sync.SyncSourceTypeValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-74：Android 导入路径的**凭据闸门**与**对端基线读数**。
 *
 * 钉住的性质（对齐 Windows SourceSyncImportRound.Preview / ApplyAsync）：
 * 1. 带**真** secrets 块的记录一件也不写库，改判 requiresApproval、理由 secrets-require-approval；
 *    覆盖是无条件的——连规划器判 NoChange 的条目也要人看过（W6-73 之前这里直接跳过，等于默认信任对端密文）；
 * 2. 同一封里干净的兄弟记录照常写入并进基线：凭据条目既不阻塞别人，也不污染基线；
 * 3. `secrets: null` 不算携带凭据（判据统一在 [SourceSyncSecrets]，Windows 的原话是 ValueKind != Null）；
 * 4. 结果对象里只出现 syncSourceId，绝不回显凭据字节；
 * 5. 对端基线**读得到**：读取发生在写入之前，所以首轮一定报"不知道"，下一轮才报已知条数；
 *    没有基线存储或读取失败都折成"不知道"，绝不让导入失败。
 */
class SourceSyncSecretsGateTest {

    // ------------------------------------------------------------ 1. 凭据不落库

    @Test
    fun a_record_carrying_credentials_is_never_written_and_asks_for_approval() {
        val store = FakeStore()
        val result = runBlocking {
            service(store).importEnvelope(
                SourceSyncCodec.encode(
                    envelope(
                        record("sync-a", revision = 2L),
                        record("sync-b", revision = 5L, secrets = credentialBlock()),
                    ),
                ),
            )
        }

        assertEquals(1, result.appliedCount)
        assertEquals(1, result.requiresApprovalCount)
        assertEquals(0, result.ignoredCount)
        assertEquals(0, result.failedCount)

        val withheld = result.requiresApproval.single()
        assertEquals("sync-b", withheld.syncSourceId)
        assertEquals(SyncRoundReasons.SECRETS_REQUIRE_APPROVAL, withheld.reason)
        assertTrue(withheld.requiresUserApproval)
        // 动作与修订号必须原样留着：审批界面要靠它们说清"要对哪一条做什么"。
        assertEquals(5L, withheld.remoteRevision)

        // 库里只有那条干净的记录；凭据记录连行都没建。
        assertEquals(listOf("sync-a"), store.snapshots.values.map { it.syncSourceId })
        assertEquals(listOf("sync-b"), result.secretsWithheldSyncSourceIds)
        assertEquals(1, result.secretsWithheldCount)
    }

    @Test
    fun a_withheld_record_does_not_block_its_siblings_and_stays_out_of_the_baseline() {
        val store = FakeStore()
        val baselineStore = InMemorySourceSyncBaselineStore()
        val result = runBlocking {
            service(store, baselineStore).importEnvelope(
                SourceSyncCodec.encode(
                    envelope(
                        record("sync-b", revision = 5L, secrets = credentialBlock()),
                        record("sync-a", revision = 2L),
                    ),
                ),
            )
        }

        assertEquals(1, result.appliedCount)
        assertEquals("sync-a", result.applied.single().candidate.syncSourceId)
        assertTrue(result.baselineSaved)
        // 基线只记"真正收敛过"的条目：被扣下的那条一条都不进。
        assertEquals(
            listOf("sync-a"),
            baselineStore.load("device-windows").entries.map { it.syncSourceId },
        )
    }

    // ------------------------------------------------------------ 2. 覆盖是无条件的

    @Test
    fun an_already_known_record_that_suddenly_carries_credentials_is_held_instead_of_ignored() {
        val store = FakeStore()
        val service = service(store)

        // 第一轮：干净的记录，正常写入（本地由此有了这条来源的权威副本）。
        val first = runBlocking {
            service.importEnvelope(SourceSyncCodec.encode(envelope(record("sync-b", revision = 3L))))
        }
        assertEquals(1, first.appliedCount)

        // 第二轮：同一来源、同一修订号、同样身份字段，但这次带了凭据块。
        // 内容指纹不含 secrets，规划器判 NoChange；凭据闸门必须把它从 ignored 拉回 requiresApproval。
        val second = runBlocking {
            service.importEnvelope(
                SourceSyncCodec.encode(envelope(record("sync-b", revision = 3L, secrets = credentialBlock()))),
            )
        }

        assertEquals(0, second.appliedCount)
        assertEquals(0, second.ignoredCount)
        assertEquals(1, second.requiresApprovalCount)
        assertEquals(SyncRoundReasons.SECRETS_REQUIRE_APPROVAL, second.requiresApproval.single().reason)
        // 本地那一条一个字节都没被改写。
        assertEquals(1, store.snapshots.size)
        assertEquals(1, store.createCalls)
        assertEquals(0, store.updateCalls)
    }

    // ------------------------------------------------------------ 3. 判据排除 JSON null

    @Test
    fun an_explicit_json_null_secrets_block_is_not_credentials() {
        // 判据本身：JsonNull 是 Kotlin 对象而不是 null，只写 != null 会把显式 null 误判成凭据。
        assertFalse(SourceSyncSecrets.carriesBlock(record("sync-a", secrets = JsonNull)))
        assertTrue(SourceSyncSecrets.carriesBlock(record("sync-a", secrets = credentialBlock())))

        val store = FakeStore()
        val result = runBlocking {
            service(store).importEnvelope(
                SourceSyncCodec.encode(envelope(record("sync-a", revision = 1L, secrets = JsonNull))),
            )
        }

        // Windows 的判据是 ValueKind != Null，所以 secrets: null 与"没有 secrets"同义。
        assertEquals(1, result.appliedCount)
        assertEquals(0, result.requiresApprovalCount)
        assertEquals(0, result.secretsWithheldCount)
        assertEquals(listOf("sync-a"), store.snapshots.values.map { it.syncSourceId })
    }

    // ------------------------------------------------------------ 4. 凭据字节不外泄

    @Test
    fun the_result_never_echoes_credential_bytes() {
        val marker = "SEALED-CREDENTIAL-MARKER-4c1f"
        val result = runBlocking {
            service(FakeStore()).importEnvelope(
                SourceSyncCodec.encode(
                    envelope(record("sync-c", revision = 1L, secrets = credentialBlock(marker))),
                ),
            )
        }

        assertFalse(result.toString().contains(marker))
        assertEquals(listOf("sync-c"), result.secretsWithheldSyncSourceIds)
    }

    // ------------------------------------------------------------ 5. 基线是读得到的

    @Test
    fun the_peer_baseline_is_read_before_the_round_and_reported_after_it() {
        val store = FakeStore()
        val baselineStore = InMemorySourceSyncBaselineStore()
        val service = service(store, baselineStore)
        val json = SourceSyncCodec.encode(envelope(record("sync-a", revision = 4L)))

        val first = runBlocking { service.importEnvelope(json) }
        // 读取在写入之前：首轮一定"不知道"，调用方不会把刚写的条目当成"以前就同步过"。
        assertFalse(first.baselineKnown)
        assertEquals(0, first.baselineEntryCount)
        assertTrue(first.baselineSaved)

        val second = runBlocking { service.importEnvelope(json) }
        assertEquals(0, second.appliedCount)
        assertTrue(second.baselineKnown)
        assertEquals(1, second.baselineEntryCount)
        assertFalse(second.baselineSaved)
    }

    @Test
    fun without_a_baseline_store_the_read_is_simply_unknown() {
        val store = FakeStore()
        val result = runBlocking {
            service(store).importEnvelope(SourceSyncCodec.encode(envelope(record("sync-a", revision = 1L))))
        }

        assertEquals(1, result.appliedCount)
        assertFalse(result.baselineKnown)
        assertEquals(0, result.baselineEntryCount)
        assertFalse(result.baselineSaved)
    }

    // ------------------------------------------------------------ 夹具

    private fun service(
        store: FakeStore,
        baselineStore: ISourceSyncBaselineStore? = null,
    ): AndroidSourceSyncService = AndroidSourceSyncService(
        store = store,
        deviceId = "device-android",
        snapshotIdFactory = { "snap-fixed" },
        clock = { 1_700_000_000_000L },
        baselineStore = baselineStore,
    )

    /** 对端密封过的凭据块：本端只判断"有没有"，内容长什么样与判定无关。 */
    private fun credentialBlock(marker: String = "SEALED-CREDENTIAL-MARKER-4c1f"): JsonElement = buildJsonObject {
        put("scheme", JsonPrimitive("aes-gcm-v1"))
        put("payload", JsonPrimitive(marker))
    }

    private fun record(
        id: String,
        wire: String = "emby",
        revision: Long = 1L,
        secrets: JsonElement? = null,
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

    private fun envelope(vararg records: SourceRecord): SourceSyncEnvelope = SourceSyncEnvelope(
        deviceId = "device-windows",
        snapshotId = "snap-windows",
        createdAtEpochMs = 1_700_000_000_000L,
        capabilities = SyncCapabilities(canSendSecrets = false, sourceTypes = emptyList()),
        sources = records.toList(),
        deletions = emptyList(),
    )

    /** 只需要 create/update/listLocalSources 的真实语义，其余方法不会被这条路径碰到。 */
    private class FakeStore : SourceSyncStore {
        val snapshots = LinkedHashMap<String, SourceSyncSourceSnapshot>()
        var createCalls = 0
        var updateCalls = 0
        private var sequence = 0

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

        override fun observeServers(): kotlinx.coroutines.flow.Flow<List<com.mediahub.model.MediaServer>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun getServer(id: String): com.mediahub.model.MediaServer? = null
    }
}
