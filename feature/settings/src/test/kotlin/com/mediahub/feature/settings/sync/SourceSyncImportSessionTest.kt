package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SourceSyncSummary
import com.mediahub.core.database.sync.AppliedSourceChange
import com.mediahub.core.database.sync.SourceSyncImportResult
import com.mediahub.core.database.sync.SourceSyncPreview
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入页状态机（纯 JVM 证据）：预览零写入、失败关闭、结果如实汇报。
 *
 * 这里用假的端口替身，断言的是"界面层会做什么"，不是数据库行为（后者由 core:database 自己的
 * 测试钉住）。最要紧的一条：没有点允许的条目绝不会被交给写库服务。
 */
class SourceSyncImportSessionTest {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun selectingAnArchiveInvalidatesAnInFlightPlaintextPreview() = runTest {
        val result = CompletableDeferred<SourceSyncPreview>()
        val apply = FakeApplyPort(ok())
        val session = SourceSyncImportSession({ result.await() }, apply)
        session.loadEnvelope(envelopeJson(), "old.json")
        val job = launch { session.preview() }
        runCurrent()
        session.requirePassphrase("passphrase-required", null, "new.mharchive")
        result.complete(ready(planWithTwoCreateAndOneUnsupported()))
        job.join()
        assertEquals(SourceSyncImportStatus.BLOCKED, session.state.value.status)
        assertEquals("new.mharchive", session.state.value.fileName)
        assertNull(session.state.value.preview)
        assertEquals(0, apply.calls)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun aLatePreviewFailureCannotBlockANewDocument() = runTest {
        val result = CompletableDeferred<SourceSyncPreview>()
        val session = SourceSyncImportSession({ result.await() }, FakeApplyPort(ok()))
        session.loadEnvelope(envelopeJson(), "old.json")
        val job = launch { session.preview() }
        runCurrent()
        session.loadEnvelope("new-envelope", "new.json")
        result.complete(SourceSyncPreview.Rejected("old-failure", null))
        job.join()
        assertEquals(SourceSyncImportStatus.READY_TO_PREVIEW, session.state.value.status)
        assertEquals("new-envelope", session.state.value.envelopeJson)
        assertNull(session.state.value.blockedReason)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun repeatedApplyWhileSuspendedOnlyCallsTheWriterOnce() = runTest {
        val result = CompletableDeferred<SourceSyncImportResult>()
        var calls = 0
        val session = SourceSyncImportSession(
            { ready(planWithTwoCreateAndOneUnsupported()) }, { calls++; result.await() },
        )
        session.loadEnvelope(envelopeJson())
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)
        val first = launch { session.apply() }
        runCurrent()
        val second = launch { session.apply() }
        runCurrent()
        result.complete(ok())
        first.join(); second.join()
        assertEquals(1, calls)
    }

    @Test
    fun previewWithoutAnEnvelopeIsBlocked() = runTest {
        val preview = FakePreviewPort(rejected("no-envelope"))
        val session = SourceSyncImportSession(preview, FakeApplyPort(ok()))

        session.preview()

        assertEquals(SourceSyncImportStatus.BLOCKED, session.state.value.status)
        assertEquals(SourceSyncImportCopy.NO_ENVELOPE, session.state.value.blockedReason)
        assertEquals(0, preview.calls)
    }

    @Test
    fun rejectedEnvelopeBlocksAndNeverCallsApply() = runTest {
        val preview = FakePreviewPort(SourceSyncPreview.Rejected("malformed-json", "bad envelope"))
        val apply = FakeApplyPort(ok())
        val session = SourceSyncImportSession(preview, apply)

        session.loadEnvelope("{}", "broken.json")
        session.preview()

        val state = session.state.value
        assertEquals(SourceSyncImportStatus.BLOCKED, state.status)
        assertEquals("malformed-json", state.blockedReason)
        assertEquals("bad envelope", state.blockedMessage)
        assertNull(state.preview)
        assertEquals(0, apply.calls)
    }

    @Test
    fun readyPreviewIsGroupedIntoFourBuckets() = runTest {
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            FakeApplyPort(ok()),
        )

        session.loadEnvelope("{}", "envelope.json")
        session.preview()

        val state = session.state.value
        assertEquals(SourceSyncImportStatus.PREVIEWED, state.status)
        assertNotNull(state.preview)
        assertEquals(4, state.preview!!.buckets.size)
        assertEquals(2, state.preview!!.countIn(SourceSyncImportBucket.APPLY))
        assertEquals(1, state.preview!!.countIn(SourceSyncImportBucket.UNSUPPORTED))
        assertEquals(0, state.preview!!.allowCount)
        assertFalse(state.preview!!.canApply)
    }

    @Test
    fun previewFailureIsReportedNotSwallowed() = runTest {
        val session = SourceSyncImportSession(
            SourceSyncPreviewPort { error("boom") },
            FakeApplyPort(ok()),
        )

        session.loadEnvelope("{}")
        session.preview()

        assertEquals(SourceSyncImportStatus.BLOCKED, session.state.value.status)
        assertEquals(SourceSyncImportCopy.PREVIEW_FAILED, session.state.value.blockedReason)
        assertEquals("boom", session.state.value.blockedMessage)
    }

    @Test
    fun applyWithoutAllowingAnythingIsBlockedAndNeverReachesTheWriter() = runTest {
        val apply = FakeApplyPort(ok())
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            apply,
        )
        session.loadEnvelope(envelopeJson())
        session.preview()

        session.apply()

        assertEquals(SourceSyncImportStatus.BLOCKED, session.state.value.status)
        assertEquals(SourceSyncImportCopy.NOTHING_ALLOWED, session.state.value.blockedReason)
        assertEquals(0, apply.calls)
    }

    @Test
    fun applyWithoutAPreviewIsBlocked() = runTest {
        val apply = FakeApplyPort(ok())
        val session = SourceSyncImportSession(FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())), apply)

        session.loadEnvelope(envelopeJson())
        session.apply()

        assertEquals(SourceSyncImportCopy.NO_PREVIEW, session.state.value.blockedReason)
        assertEquals(0, apply.calls)
    }

    @Test
    fun onlyTheAllowedEntrySurvivesIntoTheAppliedEnvelope() = runTest {
        val apply = FakeApplyPort(ok())
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            apply,
        )
        session.loadEnvelope(envelopeJson())
        session.preview()

        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)
        session.decide("sync-2", SourceSyncEntryDecision.SKIP)
        session.apply()

        assertEquals(1, apply.calls)
        val decoded = SourceSyncCodec.tryDecode(apply.received!!)
        assertTrue(decoded is SourceSyncCodec.DecodeResult.Ok)
        val envelope = (decoded as SourceSyncCodec.DecodeResult.Ok).envelope
        // 用户跳过的 sync-2 被剔除；不可允许的 sync-quantum 故意留在信封里，
        // 这样结果页才能如实报出"暂不支持 1 条"，而不是让它凭空消失。
        assertEquals(listOf("sync-1", "sync-quantum"), envelope.sources.map { it.syncSourceId })
        assertEquals(SourceSyncImportStatus.APPLIED, session.state.value.status)
    }

    @Test
    fun unknownTypeEntriesCannotBeAllowedThroughTheUi() = runTest {
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            FakeApplyPort(ok()),
        )
        session.loadEnvelope(envelopeJson())
        session.preview()

        session.decide("sync-quantum", SourceSyncEntryDecision.ALLOW)

        val unsupported = session.state.value.preview!!
            .entriesIn(SourceSyncImportBucket.UNSUPPORTED)
            .single()
        assertFalse(unsupported.allowable)
        assertEquals(SourceSyncEntryDecision.SKIP, unsupported.decision)
        assertFalse(session.state.value.preview!!.canApply)
    }

    @Test
    fun rejectedApplyResultBlocksWithItsOwnReason() = runTest {
        val rejected = SourceSyncImportResult(rejectedReason = "unsupported-schema-version:2")
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            FakeApplyPort(rejected),
        )
        session.loadEnvelope(envelopeJson())
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)

        session.apply()

        assertEquals(SourceSyncImportStatus.BLOCKED, session.state.value.status)
        assertEquals("unsupported-schema-version:2", session.state.value.blockedReason)
        assertNull(session.state.value.applied)
    }

    @Test
    fun successfulApplySummarisesEveryBucket() = runTest {
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            FakeApplyPort(ok()),
        )
        session.loadEnvelope(envelopeJson())
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)

        session.apply()

        val applied = session.state.value.applied
        assertNotNull(applied)
        assertEquals(1, applied!!.appliedCount)
        assertEquals(1, applied.requiresApprovalCount)
        assertEquals(0, applied.ignoredCount)
        assertEquals(1, applied.unsupportedCount)
        assertEquals(0, applied.failedCount)
        assertEquals(listOf("客厅 Emby"), applied.appliedTitles)
    }

    @Test
    fun resetClearsEverythingIncludingTheLoadedEnvelope() = runTest {
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            FakeApplyPort(ok()),
        )
        session.loadEnvelope(envelopeJson(), "envelope.json")
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)

        session.reset()

        val state = session.state.value
        assertEquals(SourceSyncImportStatus.IDLE, state.status)
        assertNull(state.preview)
        assertNull(state.envelopeJson)
        assertNull(state.fileName)
        assertFalse(state.canPreview)
    }

    // ---------------------------------------------------------------- 夹具

    private class FakePreviewPort(private val result: SourceSyncPreview) : SourceSyncPreviewPort {
        var calls = 0
        override suspend fun preview(envelopeJson: String): SourceSyncPreview {
            calls++
            return result
        }
    }

    private class FakeApplyPort(private val result: SourceSyncImportResult) : SourceSyncApplyPort {
        var calls = 0
        var received: String? = null
        override suspend fun apply(envelopeJson: String): SourceSyncImportResult {
            calls++
            received = envelopeJson
            return result
        }
    }

    private fun createCandidate(id: String) = SourceSyncCandidate(
        action = SourceSyncAction.CREATE,
        reason = SourceSyncReasons.NEW_SOURCE,
        syncSourceId = id,
        sourceType = "emby",
        isKnownSourceType = true,
        displayName = if (id == "sync-1") "客厅 Emby" else "书房 Emby",
    )

    private fun unsupportedCandidate() = SourceSyncCandidate(
        action = SourceSyncAction.UNSUPPORTED,
        reason = SourceSyncReasons.UNKNOWN_SOURCE_TYPE,
        syncSourceId = "sync-quantum",
        sourceType = "quantum-nas",
        isKnownSourceType = false,
        displayName = "Quantum",
    )

    private fun planWithTwoCreateAndOneUnsupported() = SourceSyncPlan(
        listOf(createCandidate("sync-1"), createCandidate("sync-2"), unsupportedCandidate()),
    )

    private fun ready(plan: SourceSyncPlan) = SourceSyncPreview.Ready(
        plan = plan,
        summary = SourceSyncSummary.fromPlan(plan),
        deviceId = "device-a",
        snapshotId = "snap-1",
    )

    private fun rejected(reason: String) = SourceSyncPreview.Rejected(reason, null)

    private fun ok() = SourceSyncImportResult(
        deviceId = "device-a",
        snapshotId = "snap-1",
        applied = listOf(
            AppliedSourceChange(
                serverId = "srv-1",
                candidate = createCandidate("sync-1"),
                contentHash = "hash-1",
            ),
        ),
        requiresApproval = listOf(createCandidate("sync-2")),
        ignored = emptyList(),
        unsupported = listOf(unsupportedCandidate()),
        failed = emptyList(),
    )

    private fun envelopeJson(): String = SourceSyncCodec.encode(
        SourceSyncEnvelope(
            deviceId = "device-a",
            snapshotId = "snap-1",
            sources = listOf(
                com.mediahub.core.common.sync.SourceRecord(
                    syncSourceId = "sync-1",
                    sourceType = com.mediahub.core.common.sync.SyncSourceTypeValue.parse("emby"),
                    displayName = "客厅 Emby",
                ),
                com.mediahub.core.common.sync.SourceRecord(
                    syncSourceId = "sync-2",
                    sourceType = com.mediahub.core.common.sync.SyncSourceTypeValue.parse("emby"),
                    displayName = "书房 Emby",
                ),
                com.mediahub.core.common.sync.SourceRecord(
                    syncSourceId = "sync-quantum",
                    sourceType = com.mediahub.core.common.sync.SyncSourceTypeValue.parse("quantum-nas"),
                    displayName = "Quantum",
                ),
            ),
        ),
    )
}
