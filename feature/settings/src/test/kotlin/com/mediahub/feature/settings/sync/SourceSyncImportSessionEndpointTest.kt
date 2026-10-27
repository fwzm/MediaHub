package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEndpointSummary
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncNotImportedEndpoint
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SourceSyncSummary
import com.mediahub.core.database.sync.AppliedSourceChange
import com.mediahub.core.database.sync.SourceSyncImportResult
import com.mediahub.core.database.sync.SourceSyncPreview
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-72：结果页多出来的两条事实（未参与同步的次要端点 / 是否记进对端基线）。
 *
 * 这一层的职责是**如实转述**写库层的结论，不自己再判断一次：
 * 端点条数来自 Windows 同名同序的 SourceSyncEndpointPolicy.summarize，
 * 基线结果来自 ISourceSyncBaselineStore.save 的返回值（合并不让条目倒退）。
 */
class SourceSyncImportSessionEndpointTest {

    @Test
    fun endpoint_and_baseline_facts_reach_the_result_summary() = runTest {
        val result = ok().copy(
            endpointSummary = SourceSyncEndpointSummary(
                items = listOf(
                    SourceSyncNotImportedEndpoint(
                        syncSourceId = "sync-1",
                        displayName = "客厅 Emby",
                        url = "http://nas-a:8097/emby",
                        sortOrder = 1,
                    ),
                ),
                recordsWithAlternatives = 1,
                recordsWithoutEndpoint = 0,
            ),
            baselineSaved = true,
        )
        val session = SourceSyncImportSession(
            FakePreviewPort(SourceSyncPreview.Ready(
                plan = SourceSyncPlan(listOf(createCandidate("sync-1"))),
                summary = SourceSyncSummary.fromPlan(SourceSyncPlan(listOf(createCandidate("sync-1")))),
                deviceId = "device-a",
                snapshotId = "snap-1",
            )),
            FakeApplyPort(result),
        )
        session.loadEnvelope(envelopeJson())
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)

        session.apply()

        val applied = session.state.value.applied
        assertNotNull(applied)
        assertEquals(1, applied!!.notImportedEndpointCount)
        assertEquals(1, applied.recordsWithAlternativeEndpoints)
        assertTrue(applied.baselineSaved)
        assertEquals(0, applied.baselineRejectedCount)
    }

    @Test
    fun baseline_rejections_are_counted_not_hidden() = runTest {
        val result = ok().copy(
            baselineSaved = true,
            baselineRejectedSyncSourceIds = listOf("sync-1"),
            baselineRejectionReason = "baseline-stale-revision",
        )
        val plan = SourceSyncPlan(listOf(createCandidate("sync-1")))
        val session = SourceSyncImportSession(
            FakePreviewPort(
                SourceSyncPreview.Ready(
                    plan = plan,
                    summary = SourceSyncSummary.fromPlan(plan),
                    deviceId = "device-a",
                    snapshotId = "snap-1",
                ),
            ),
            FakeApplyPort(result),
        )
        session.loadEnvelope(envelopeJson())
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)

        session.apply()

        assertEquals(1, session.state.value.applied!!.baselineRejectedCount)
    }

    @Test
    fun envelopes_without_extra_endpoints_report_zero() = runTest {
        val plan = SourceSyncPlan(listOf(createCandidate("sync-1")))
        val session = SourceSyncImportSession(
            FakePreviewPort(
                SourceSyncPreview.Ready(
                    plan = plan,
                    summary = SourceSyncSummary.fromPlan(plan),
                    deviceId = "device-a",
                    snapshotId = "snap-1",
                ),
            ),
            FakeApplyPort(ok()),
        )
        session.loadEnvelope(envelopeJson())
        session.preview()
        session.decide("sync-1", SourceSyncEntryDecision.ALLOW)

        session.apply()

        val applied = session.state.value.applied!!
        assertEquals(0, applied.notImportedEndpointCount)
        assertEquals(0, applied.recordsWithAlternativeEndpoints)
        assertFalse(applied.baselineSaved)
    }

    // ------------------------------------------------------------ 夹具

    private class FakePreviewPort(private val result: SourceSyncPreview) : SourceSyncPreviewPort {
        override suspend fun preview(envelopeJson: String): SourceSyncPreview = result
    }

    private class FakeApplyPort(private val result: SourceSyncImportResult) : SourceSyncApplyPort {
        override suspend fun apply(envelopeJson: String): SourceSyncImportResult = result
    }

    private fun createCandidate(id: String) = SourceSyncCandidate(
        action = SourceSyncAction.CREATE,
        reason = SourceSyncReasons.NEW_SOURCE,
        syncSourceId = id,
        sourceType = "emby",
        isKnownSourceType = true,
        displayName = "客厅 Emby",
    )

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
            ),
        ),
    )
}
