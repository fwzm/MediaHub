package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SourceSyncSummary
import com.mediahub.core.database.sync.SourceSyncApplyReport
import com.mediahub.core.database.sync.SourceSyncPreview
import com.mediahub.core.database.sync.SourceSyncWrittenSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 导入页状态机（纯 JVM 证据）：预览零写入、失败关闭、结果如实汇报。
 *
 * 这里用假的端口替身，断言的是"界面层会做什么"，不是数据库行为（后者由 core:database 自己的
 * 测试钉住）。最要紧的一条：没有点允许的条目绝不会被写进库 —— 它要么进跳过名单（被闸门裁掉），
 * 要么压根不在放行名单里，绝不靠"把信封改小"来变相实现。
 */
class SourceSyncImportSessionTest {

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
    fun theWholeEnvelopeTravelsWithBothDecisionLists() = runTest {
        val apply = FakeApplyPort(ok())
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            apply,
        )
        val json = envelopeJson()
        session.loadEnvelope(json)
        session.preview()

        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)
        session.decide("sync-2", SourceSyncEntryDecision.SKIP)
        session.apply()

        assertEquals(1, apply.calls)
        // 信封整份透传：裁决只走名单。信封少一条，回执就没法解释为什么少。
        assertEquals(json, apply.received)
        assertEquals(setOf("sync-1"), apply.allowedKeys)
        assertEquals(setOf("sync-2"), apply.skippedKeys)
        assertEquals(SourceSyncImportStatus.APPLIED, session.state.value.status)
    }

    @Test
    fun anUndecidedAllowableEntryIsSkippedNotWritten() = runTest {
        val apply = FakeApplyPort(ok())
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            apply,
        )
        session.loadEnvelope(envelopeJson())
        session.preview()

        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)
        session.apply()

        // sync-2 从没被点过：它必须进跳过名单（失败关闭），而不是被当成"默认允许"。
        assertEquals(setOf("sync-1"), apply.allowedKeys)
        assertEquals(setOf("sync-2"), apply.skippedKeys)
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
        val rejected = SourceSyncApplyReport(rejectedReason = "unsupported-schema-version:2")
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
        // 回执桶：这一轮真正发生了什么。
        assertEquals(1, applied!!.appliedCount)
        assertEquals(0, applied.skippedCount)
        assertEquals(1, applied.withheldCount)
        assertEquals(0, applied.failedCount)
        assertEquals(0, applied.removedCount)
        assertEquals(1, applied.notAppliedCount)
        // 规划桶：预览里本来就有多少。这里必须是 0 —— 三份候选里没有一份需要确认，
        // 旧夹具却自称回执里带了一条 requiresApproval，于是这条断言在假数据上绿了很久。
        assertEquals(0, applied.requiresApprovalCount)
        assertEquals(0, applied.ignoredCount)
        assertEquals(1, applied.unsupportedCount)
        assertEquals(listOf("客厅 Emby"), applied.appliedTitles)
        assertEquals("srv-1", applied.appliedSources.single().serverId)
        assertEquals("sync-1", applied.appliedSources.single().syncSourceId)
        assertEquals("emby", applied.appliedSources.single().sourceTypeRaw)
    }

    @Test
    fun aRemovalCountsAsAppliedInTheReceiptButNotAsAWriteInTheSummary() = runTest {
        // 回执的 applied 含删除（对端删了就得连凭据一起移除），结果页说「已写入」时可不能把它算进去。
        val report = SourceSyncApplyReport(
            applied = 2,
            wrote = listOf(SourceSyncWrittenSource(syncSourceId = "sync-1", serverId = "srv-1")),
            removedServerIds = listOf("srv-old"),
            removedSyncSourceIds = listOf("sync-gone"),
        )
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            FakeApplyPort(report),
        )
        session.loadEnvelope(envelopeJson())
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)

        session.apply()

        val applied = session.state.value.applied!!
        assertEquals(1, applied.appliedCount)
        assertEquals(1, applied.removedCount)
        assertEquals(1, applied.appliedSources.size)
    }

    @Test
    fun aWrittenEntryMissingFromThePreviewStillReachesTheVerificationList() = runTest {
        // 回执与预览对不上时宁可退化展示，也不能把这条丢了 —— 丢了就永远补不了凭据。
        val stray = SourceSyncApplyReport(
            applied = 1,
            wrote = listOf(SourceSyncWrittenSource(syncSourceId = "sync-ghost", serverId = "srv-9")),
        )
        val session = SourceSyncImportSession(
            FakePreviewPort(ready(planWithTwoCreateAndOneUnsupported())),
            FakeApplyPort(stray),
        )
        session.loadEnvelope(envelopeJson())
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)

        session.apply()

        val source = session.state.value.applied!!.appliedSources.single()
        assertEquals("srv-9", source.serverId)
        assertEquals("sync-ghost", source.syncSourceId)
        assertEquals("sync-ghost", source.title)
        assertNull(source.sourceTypeRaw)
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

    private class FakeApplyPort(private val result: SourceSyncApplyReport) : SourceSyncApplyPort {
        var calls = 0
        var received: String? = null
        var allowedKeys: Set<String> = emptySet()
        var skippedKeys: Set<String> = emptySet()
        override suspend fun apply(
            envelopeJson: String,
            allowedKeys: Set<String>,
            skippedKeys: Set<String>,
        ): SourceSyncApplyReport {
            calls++
            received = envelopeJson
            this.allowedKeys = allowedKeys
            this.skippedKeys = skippedKeys
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

    /** 一份「写入一条、扣住一条」的回执：扣住的是不可允许的 sync-quantum。 */
    private fun ok() = SourceSyncApplyReport(
        peerDeviceId = "device-a",
        applied = 1,
        skipped = 0,
        withheld = 1,
        refused = 0,
        wrote = listOf(SourceSyncWrittenSource(syncSourceId = "sync-1", serverId = "srv-1")),
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
