package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.InMemorySourceSyncBaselineStore
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncBaseline
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
        assertEquals("sync-a", report.wrote.single().syncSourceId)
        assertEquals("srv-1", report.wrote.single().serverId)
        assertTrue(report.hasWrites)
        assertEquals("已写入本地 1 条 / 跳过 0 条 / 保留未应用 0 条", report.summary)
        // 基线只在这一轮真的写进去之后推进，且条数取自保存后的真实值。
        assertTrue(report.baselineUpdated)
        assertEquals(1, report.baselineEntryCount)
        assertEquals(1, baselines.writeCount)
        assertEquals("sync-a", store.snapshots["srv-1"]?.syncSourceId)
    }

    @Test
    fun every_written_record_keeps_its_identity_beside_the_new_server_id() = runBlocking {
        // 结果页要靠这个配对把「补录并验证」对到具体来源：serverId 是本机新造的，身份只在对端信封里。
        val store = FakeStore()
        val service = service(store, InMemorySourceSyncBaselineStore())
        val json = SourceSyncCodec.encode(
            envelope(sources = listOf(record("sync-a", revision = 3L), record("sync-b", revision = 3L))),
        )

        val report = service.applyRound(json)

        assertEquals(2, report.applied)
        assertEquals(
            listOf("sync-a" to "srv-1", "sync-b" to "srv-2"),
            report.wrote.map { written -> written.syncSourceId to written.serverId },
        )
        assertEquals(listOf("srv-1", "srv-2"), report.wroteServerIds)
        assertEquals(0, report.removedServerIds.size)
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
        // W6-83：只给码不给身份，界面就不知道被留下的是哪一条。
        assertEquals(listOf("sync-a"), report.withheldEntries.map { it.syncSourceId })
        assertEquals(
            listOf(SyncRoundReasons.SECRETS_REQUIRE_APPROVAL),
            report.withheldEntries.map { it.reason },
        )
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
        assertEquals(listOf("sync-a"), report.withheldEntries.map { it.syncSourceId })
        assertEquals(0, store.createCalls)
    }

    @Test
    fun every_withheld_source_keeps_its_identity_and_its_own_reason() = runBlocking {
        // 「保留未应用 2 条」对用户是死路：不知道是哪两条、也不知道各自卡在哪一步。
        // 回执要逐条带上身份与原因码。顺序是合并器排定的（按身份升序），不是信封顺序：
        // 闸门只按传进来的先后追加，用户看到的列表因此两轮之间稳定。
        val store = FakeStore()
        val json = SourceSyncCodec.encode(
            envelope(
                sources = listOf(
                    record("sync-secret", secrets = JsonPrimitive("sealed-blob")),
                    record("sync-odd", wire = "MyThing"),
                ),
            ),
        )

        val report = service(store).applyRound(
            json,
            allowedSyncSourceIds = listOf("sync-secret", "sync-odd"),
        )

        assertEquals(2, report.withheld)
        assertEquals(0, report.applied)
        assertEquals(listOf("sync-odd", "sync-secret"), report.withheldEntries.map { it.syncSourceId })
        assertEquals(
            listOf(SyncRoundReasons.UNKNOWN_SOURCE_TYPE, SyncRoundReasons.SECRETS_REQUIRE_APPROVAL),
            report.withheldEntries.map { it.reason },
        )
        // 查表口径与 refusedSyncSourceIds 一致：去空白 + ASCII 大小写不敏感。
        assertEquals(SyncRoundReasons.SECRETS_REQUIRE_APPROVAL, report.withheldReasonOf(" SYNC-SECRET "))
        assertEquals(null, report.withheldReasonOf("sync-absent"))
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
        // 没接 remover 时删除仍然只能拒绝：存储层没有删除方法，拒绝就是拒绝，不能假装应用过。
        assertEquals(AndroidSourceSyncService.DELETE_NOT_SUPPORTED, report.removalFailureOf("sync-del"))
        assertEquals(1, store.snapshots.size)
        assertEquals(0, store.updateCalls)
        assertFalse(report.baselineUpdated)
    }

    @Test
    fun a_write_failure_and_a_refused_deletion_each_keep_their_own_code_and_action() = runBlocking {
        // 一份信封里同时出现「写不下去」与「删不了」：两条都得点名，动作不能混（写 ≠ 删）。
        val store = FakeStore()
        store.failWrites = true
        store.seed(snapshot(serverId = "srv-del", syncSourceId = "sync-del", revision = 1L))
        val json = SourceSyncCodec.encode(
            envelope(
                sources = listOf(record("sync-a", revision = 2L)),
                deletions = listOf(
                    SourceTombstone(
                        syncSourceId = "sync-del",
                        deletedAtEpochMs = 1_700_000_100_000L,
                        recordRevision = 2L,
                    ),
                ),
            ),
        )

        val report = service(store).applyRound(json, allowedSyncSourceIds = listOf("sync-a", "sync-del"))

        assertEquals(2, report.refused)
        assertEquals(listOf("sync-a", "sync-del"), report.refusals.map { it.syncSourceId })
        assertEquals(
            listOf(SourceSyncAction.CREATE, SourceSyncAction.DELETE),
            report.refusals.map { it.action },
        )
        assertTrue(
            report.refusals.first().reason.startsWith(AndroidSourceSyncService.WRITE_FAILED + ":"),
        )
        assertEquals(AndroidSourceSyncService.DELETE_NOT_SUPPORTED, report.refusals.last().reason)
        // 删除那条同时留在 removalFailures 里（既有调用方仍在读它），两处口径必须一致。
        assertEquals(AndroidSourceSyncService.DELETE_NOT_SUPPORTED, report.removalFailureOf("sync-del"))
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
        // W6-82：拒绝要带身份、动作与稳定码，光有计数用户无从下手。
        val refusal = report.refusals.single()
        assertEquals("sync-a", refusal.syncSourceId)
        assertEquals(SourceSyncAction.CREATE, refusal.action)
        assertTrue(refusal.reason.startsWith(AndroidSourceSyncService.WRITE_FAILED + ":"))
        assertEquals(refusal.reason, report.refusalOf(" SYNC-A "))
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

    // ------------------------------------------------ W6-79 跨端删除（对齐 Windows RemoveRemoteSourceAsync）

    @Test
    fun an_allowed_deletion_removes_the_local_source_through_the_remover() = runBlocking {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "sync-del", revision = 1L))
        val baselines = InMemorySourceSyncBaselineStore()
        val remover = FakeRemover(store)
        val json = SourceSyncCodec.encode(
            envelope(deletions = listOf(SourceTombstone(syncSourceId = "sync-del", deletedAtEpochMs = 1_700_000_100_000L, recordRevision = 2L))),
        )

        val report = service(store, baselines, remover)
            .applyRound(json, allowedSyncSourceIds = listOf("sync-del"))

        assertEquals(1, report.applied)
        assertEquals(0, report.refused)
        assertEquals(1, report.removedCount)
        assertEquals(listOf("srv-local"), report.removedServerIds)
        assertEquals(listOf("sync-del"), report.removedSyncSourceIds)
        // 身份比较与闸门同口径：trim + 大小写不敏感。
        assertTrue(report.isRemoved(" SYNC-DEL "))
        assertTrue(store.snapshots.isEmpty())
        assertEquals(listOf("srv-local"), remover.calls)
        // 删除不是写入：基线不因删除而推进（Windows 只在有非 null 记录写入时推进）。
        assertFalse(report.baselineUpdated)
        assertEquals(0, baselines.writeCount)
        assertEquals("已写入本地 1 条 / 跳过 0 条 / 保留未应用 0 条", report.summary)
    }

    @Test
    fun a_local_media_source_is_never_removed_across_devices() = runBlocking {
        val store = FakeStore()
        store.seed(
            snapshot(
                serverId = "srv-local-media",
                syncSourceId = "sync-media",
                revision = 1L,
                type = ServerType.LOCAL,
            ),
        )
        val remover = FakeRemover(store)
        val json = SourceSyncCodec.encode(
            envelope(deletions = listOf(SourceTombstone(syncSourceId = "sync-media", deletedAtEpochMs = 1_700_000_100_000L, recordRevision = 2L))),
        )

        val report = service(store, null, remover)
            .applyRound(json, allowedSyncSourceIds = listOf("sync-media"))

        assertEquals(0, report.applied)
        assertEquals(1, report.refused)
        assertEquals(AndroidSourceSyncService.REMOVE_LOCAL_MEDIA, report.removalFailureOf("sync-media"))
        // 本地媒体源是用户磁盘上的东西，对端说了不算：remover 一次都不该被调用。
        assertTrue(remover.calls.isEmpty())
        assertEquals(1, store.snapshots.size)
    }

    @Test
    fun a_deletion_for_a_source_this_device_no_longer_has_is_refused() = runBlocking {
        val store = FakeStore()
        val baselines = InMemorySourceSyncBaselineStore()
        // 基线认识它、库里没有它：对端指着一条已经不在本机的来源。
        baselines.save(
            "device-windows",
            SourceSyncBaseline.fromRecords(
                listOf(record("sync-gone", revision = 1L)),
                "device-windows",
                "snap-windows",
            ),
        )
        val remover = FakeRemover(store)
        val json = SourceSyncCodec.encode(
            envelope(deletions = listOf(SourceTombstone(syncSourceId = "sync-gone", deletedAtEpochMs = 1_700_000_100_000L, recordRevision = 2L))),
        )

        val report = service(store, baselines, remover)
            .applyRound(json, allowedSyncSourceIds = listOf("sync-gone"))

        assertEquals(0, report.applied)
        assertEquals(1, report.refused)
        assertEquals(AndroidSourceSyncService.REMOVE_MISSING, report.removalFailureOf("sync-gone"))
        assertTrue(remover.calls.isEmpty())
    }

    @Test
    fun a_deletion_matches_the_local_source_regardless_of_spelling() = runBlocking {
        // 对端墓碑写的是 sync-del，本机行存的是 Sync-Del：身份比对必须走 trim + ASCII 小写，
        // 否则删除会以「本机没有这条来源」被拒绝，而那条来源其实还在（删不掉也报错，两处都错）。
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "Sync-Del", revision = 1L))
        val baselines = InMemorySourceSyncBaselineStore()
        val remover = FakeRemover(store)
        val service = service(store, baselines, remover)
        val json = SourceSyncCodec.encode(
            envelope(
                sources = emptyList(),
                deletions = listOf(SourceTombstone("sync-del", 1_700_000_000_000L, recordRevision = 2L)),
            ),
        )

        val report = service.applyRound(json, allowedSyncSourceIds = listOf("sync-del"))

        assertEquals(1, report.applied)
        assertEquals(listOf("srv-local"), remover.calls)
        assertTrue(store.snapshots.isEmpty())
    }

    @Test
    fun a_failing_remover_is_reported_as_refused_instead_of_thrown() = runBlocking {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "sync-del", revision = 1L))
        val remover = FakeRemover(store)
        remover.fail = true
        val json = SourceSyncCodec.encode(
            envelope(deletions = listOf(SourceTombstone(syncSourceId = "sync-del", deletedAtEpochMs = 1_700_000_100_000L, recordRevision = 2L))),
        )

        val report = service(store, null, remover)
            .applyRound(json, allowedSyncSourceIds = listOf("sync-del"))

        assertEquals(0, report.applied)
        assertEquals(1, report.refused)
        assertEquals(AndroidSourceSyncService.REMOVE_FAILED, report.removalFailureOf("sync-del"))
        // 删失败时行必须还在：凭据没清干净绝不能当成已删除。
        assertEquals(1, store.snapshots.size)
        assertEquals(listOf("srv-local"), remover.calls)
    }

    @Test
    fun a_remover_failure_keeps_the_step_code_it_was_given() = runBlocking {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "sync-del", revision = 1L))
        val remover = FakeRemover(store)
        remover.fail = true
        remover.failureReason = SourceSyncRemovalCodes.failedAt(SourceSyncRemovalStep.SESSION_STORE)
        val json = SourceSyncCodec.encode(
            envelope(deletions = listOf(SourceTombstone("sync-del", 1_700_000_100_000L, recordRevision = 2L))),
        )

        val report = service(store, null, remover)
            .applyRound(json, allowedSyncSourceIds = listOf("sync-del"))

        assertEquals(0, report.applied)
        assertEquals(1, report.refused)
        // 步骤码必须原样进回执：只报 local-source-remove-failed 时用户不知道是重试还是手动清。
        assertEquals(
            "local-source-remove-failed:session-store",
            report.removalFailureOf("sync-del"),
        )
        // 逐条拒绝清单与 removalFailures 用同一个码：界面两处不能一个说步骤、一个说兜底。
        assertEquals(report.removalFailureOf("sync-del"), report.refusals.single().reason)
        // 删失败时行必须还在。
        assertEquals(1, store.snapshots.size)
    }

    @Test
    fun a_source_that_vanished_before_the_remover_ran_is_reported_as_missing_not_failed() = runBlocking {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "sync-del", revision = 1L))
        val remover = FakeRemover(store)
        remover.missing = true
        val json = SourceSyncCodec.encode(
            envelope(deletions = listOf(SourceTombstone("sync-del", 1_700_000_100_000L, recordRevision = 2L))),
        )

        val report = service(store, null, remover)
            .applyRound(json, allowedSyncSourceIds = listOf("sync-del"))

        assertEquals(0, report.applied)
        assertEquals(1, report.refused)
        // 「本来就没有」不是故障：码必须是 missing，不能被折成 remove-failed 骗人去重试。
        assertEquals(AndroidSourceSyncService.REMOVE_MISSING, report.removalFailureOf("sync-del"))
        assertEquals(listOf("srv-local"), remover.calls)
    }

    @Test
    fun a_round_can_write_one_source_and_remove_another() = runBlocking {
        val store = FakeStore()
        store.seed(snapshot(serverId = "srv-local", syncSourceId = "sync-del", revision = 1L))
        val baselines = InMemorySourceSyncBaselineStore()
        val remover = FakeRemover(store)
        val json = SourceSyncCodec.encode(
            envelope(
                sources = listOf(record("sync-a", revision = 3L)),
                deletions = listOf(SourceTombstone(syncSourceId = "sync-del", deletedAtEpochMs = 1_700_000_100_000L, recordRevision = 2L)),
            ),
        )

        val report = service(store, baselines, remover)
            .applyRound(json, allowedSyncSourceIds = listOf("sync-del"))

        assertEquals(2, report.applied)
        assertEquals(1, report.removedCount)
        assertEquals(listOf("srv-1"), report.wroteServerIds)
        assertEquals(listOf("srv-local"), report.removedServerIds)
        // 基线只收真正写入的记录，删除不进基线（与 Windows ApplyAsync 同口径）。
        assertEquals(1, report.baselineEntryCount)
        assertEquals(listOf("sync-a"), baselines.load("device-windows").entries.map { it.syncSourceId })
    }

    // ------------------------------------------------------------ 夹具

    private fun service(
        store: FakeStore,
        baselineStore: InMemorySourceSyncBaselineStore? = null,
        remover: SourceSyncSourceRemover? = null,
    ): AndroidSourceSyncService = AndroidSourceSyncService(
        store = store,
        deviceId = "device-android",
        snapshotIdFactory = { "snap-fixed" },
        clock = { 1_700_000_000_000L },
        baselineStore = baselineStore,
        sourceRemover = remover,
    )

    /**
     * 假 remover：真的把行从内存 store 里删掉，好让「库里的行没了」与「回执说删了」互相印证。
     *
     * W6-88 起返回值是三种落点：`fail` 时上报 [failureReason]（可带步骤后缀），`missing` 时假装行已被别处删掉。
     */
    private class FakeRemover(private val store: FakeStore) : SourceSyncSourceRemover {
        val calls = ArrayList<String>()
        var fail = false
        var missing = false
        var failureReason: String = AndroidSourceSyncService.REMOVE_FAILED

        override suspend fun removeSource(serverId: String): SourceSyncRemoveOutcome {
            calls.add(serverId)
            if (fail) return SourceSyncRemoveOutcome.Failed(failureReason)
            if (missing) return SourceSyncRemoveOutcome.Missing
            return if (store.snapshots.remove(serverId) != null) {
                SourceSyncRemoveOutcome.Removed
            } else {
                SourceSyncRemoveOutcome.Missing
            }
        }
    }

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
        type: ServerType = ServerType.EMBY,
    ): SourceSyncSourceSnapshot = SourceSyncSourceSnapshot(
        serverId = serverId,
        name = "本地 " + serverId,
        serverType = type,
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
