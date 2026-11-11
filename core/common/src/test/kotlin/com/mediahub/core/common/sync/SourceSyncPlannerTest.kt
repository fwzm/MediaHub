package com.mediahub.core.common.sync

import com.mediahub.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSyncPlannerTest {

    private fun record(
        syncSourceId: String = "local-1",
        sourceType: String = "emby",
        displayName: String = "客厅 Emby",
        accountScope: String? = "alice",
        endpoints: List<SourceEndpoint> = listOf(SourceEndpoint(url = "http://nas:8096/emby", isPrimary = true)),
        recordRevision: Long = 3L,
    ): SourceRecord = SourceRecord(
        syncSourceId = syncSourceId,
        sourceType = SyncSourceTypeValue.parse(sourceType),
        displayName = displayName,
        accountScope = accountScope,
        endpoints = endpoints,
        recordRevision = recordRevision,
    )

    private fun local(
        serverId: String,
        syncSourceId: String? = null,
        type: ServerType? = ServerType.EMBY,
        url: String? = "http://nas:8096/emby",
        accountScope: String? = "alice",
        displayName: String? = "客厅 Emby",
        recordRevision: Long = 0L,
    ): LocalSourceRecord = LocalSourceRecord(
        serverId = serverId,
        syncSourceId = syncSourceId,
        type = type,
        url = url,
        accountScope = accountScope,
        displayName = displayName,
        recordRevision = recordRevision,
    )

    private fun plan(
        sources: List<SourceRecord> = emptyList(),
        deletions: List<SourceTombstone> = emptyList(),
        locals: List<LocalSourceRecord> = emptyList(),
        createdAtEpochMs: Long = 1L,
    ): SourceSyncPlan = SourceSyncPlanner.plan(
        SourceSyncEnvelope(
            deviceId = "dev-a",
            snapshotId = "snap-a",
            createdAtEpochMs = createdAtEpochMs,
            sources = sources,
            deletions = deletions,
        ),
        locals,
    )

    @Test
    fun create_when_no_local_source_matches() {
        val candidate = plan(sources = listOf(record())).items.single()
        assertEquals(SourceSyncAction.CREATE, candidate.action)
        assertEquals(SourceSyncReasons.NEW_SOURCE, candidate.reason)
        assertFalse(candidate.requiresUserApproval)
        assertEquals("http://nas:8096/emby", candidate.normalizedUrl)
        assertEquals("local-1", candidate.syncSourceId)
        assertEquals(3L, candidate.remoteRevision)
    }

    @Test
    fun create_requires_approval_when_there_is_no_dialable_endpoint() {
        val candidate = plan(sources = listOf(record(endpoints = emptyList()))).items.single()
        assertEquals(SourceSyncAction.CREATE, candidate.action)
        assertEquals(SourceSyncReasons.NO_USABLE_ENDPOINT, candidate.reason)
        assertTrue(candidate.requiresUserApproval)
        assertNull(candidate.normalizedUrl)
    }

    @Test
    fun unknown_source_type_is_unsupported_and_the_raw_string_is_kept() {
        val candidate = plan(sources = listOf(record(sourceType = "Quantum-NAS"))).items.single()
        assertEquals(SourceSyncAction.UNSUPPORTED, candidate.action)
        assertEquals(SourceSyncReasons.UNKNOWN_SOURCE_TYPE, candidate.reason)
        assertEquals("Quantum-NAS", candidate.sourceType)
        assertFalse(candidate.isKnownSourceType)
        assertFalse(candidate.requiresUserApproval)
    }

    @Test
    fun known_but_unsupported_type_is_unsupported_for_this_client() {
        val envelope = SourceSyncEnvelope(
            deviceId = "dev-a",
            snapshotId = "snap-a",
            createdAtEpochMs = 1L,
            sources = listOf(record(sourceType = "fn-nas")),
        )
        val plan = SourceSyncPlanner.plan(
            envelope,
            emptyList(),
            { AndroidSourceTypeResolution(SyncSourceTypeValue.parse(it.raw), null) },
            { null },
        )
        val candidate = plan.items.single()
        assertEquals(SourceSyncAction.UNSUPPORTED, candidate.action)
        assertEquals(SourceSyncReasons.NOT_SUPPORTED_ON_WINDOWS, candidate.reason)
    }

    @Test
    fun higher_remote_revision_updates_the_matching_local_source() {
        val candidate = plan(
            sources = listOf(record(recordRevision = 3L)),
            locals = listOf(local(serverId = "srv-9", syncSourceId = "local-1", recordRevision = 2L)),
        ).items.single()
        assertEquals(SourceSyncAction.UPDATE, candidate.action)
        assertEquals(SourceSyncReasons.HIGHER_REVISION, candidate.reason)
        assertEquals("srv-9", candidate.serverId)
        assertEquals(2L, candidate.localRevision)
        assertFalse(candidate.requiresUserApproval)
    }

    @Test
    fun equal_revision_with_identical_content_is_no_change() {
        val candidate = plan(
            sources = listOf(record(recordRevision = 3L)),
            locals = listOf(local(serverId = "srv-9", syncSourceId = "local-1", recordRevision = 3L)),
        ).items.single()
        assertEquals(SourceSyncAction.NO_CHANGE, candidate.action)
        assertEquals(SourceSyncReasons.UP_TO_DATE, candidate.reason)
        assertFalse(candidate.requiresUserApproval)
    }

    @Test
    fun equal_revision_with_a_different_display_name_is_a_conflict() {
        val candidate = plan(
            sources = listOf(record(recordRevision = 3L, displayName = "客厅 Emby")),
            locals = listOf(
                local(serverId = "srv-9", syncSourceId = "local-1", displayName = "客厅 Emby 旧", recordRevision = 3L),
            ),
        ).items.single()
        assertEquals(SourceSyncAction.CONFLICT, candidate.action)
        assertEquals(SourceSyncReasons.SAME_REVISION_DIFFERENT_CONTENT, candidate.reason)
        assertTrue(candidate.requiresUserApproval)
        assertEquals("srv-9", candidate.serverId)
    }

    @Test
    fun equal_revision_with_a_different_account_scope_is_a_conflict() {
        val candidate = plan(
            sources = listOf(record(recordRevision = 3L, accountScope = "alice")),
            locals = listOf(
                local(serverId = "srv-9", syncSourceId = "local-1", accountScope = "bob", recordRevision = 3L),
            ),
        ).items.single()
        assertEquals(SourceSyncAction.CONFLICT, candidate.action)
        assertEquals(SourceSyncReasons.SAME_REVISION_DIFFERENT_CONTENT, candidate.reason)
    }

    @Test
    fun lower_remote_revision_is_ignored_as_stale() {
        val candidate = plan(
            sources = listOf(record(recordRevision = 2L)),
            locals = listOf(local(serverId = "srv-9", syncSourceId = "local-1", recordRevision = 5L)),
        ).items.single()
        assertEquals(SourceSyncAction.IGNORE_STALE, candidate.action)
        assertEquals(SourceSyncReasons.STALE_REVISION, candidate.reason)
        assertFalse(candidate.requiresUserApproval)
    }

    @Test
    fun two_local_sources_claiming_the_same_sync_id_is_a_conflict() {
        val candidate = plan(
            sources = listOf(record()),
            locals = listOf(
                local(serverId = "srv-1", syncSourceId = "local-1", recordRevision = 1L),
                local(serverId = "srv-2", syncSourceId = "LOCAL-1", recordRevision = 1L),
            ),
        ).items.single()
        assertEquals(SourceSyncAction.CONFLICT, candidate.action)
        assertEquals(SourceSyncReasons.DUPLICATE_LOCAL_SYNC_ID, candidate.reason)
        assertTrue(candidate.requiresUserApproval)
        assertEquals(listOf("srv-1", "srv-2"), candidate.candidateServerIds)
    }

    @Test
    fun a_pre_w6_local_source_is_adopted_when_all_three_identity_parts_agree() {
        val candidate = plan(
            sources = listOf(record()),
            locals = listOf(local(serverId = "srv-legacy", url = "http://NAS:8096/emby/", recordRevision = 7L)),
        ).items.single()
        assertEquals(SourceSyncAction.ADOPT, candidate.action)
        assertEquals(SourceSyncReasons.LEGACY_IDENTITY_MATCH, candidate.reason)
        assertEquals("srv-legacy", candidate.serverId)
        assertEquals(7L, candidate.localRevision)
        assertTrue(candidate.requiresUserApproval)
    }

    @Test
    fun several_legacy_candidates_are_ambiguous_and_never_merged_by_name() {
        val candidate = plan(
            sources = listOf(record()),
            locals = listOf(
                local(serverId = "srv-1", url = "http://nas:8096/emby"),
                local(serverId = "srv-2", url = "http://NAS:8096/emby/"),
            ),
        ).items.single()
        assertEquals(SourceSyncAction.CONFLICT, candidate.action)
        assertEquals(SourceSyncReasons.AMBIGUOUS_LEGACY_IDENTITY, candidate.reason)
        assertTrue(candidate.requiresUserApproval)
        assertEquals(listOf("srv-1", "srv-2"), candidate.candidateServerIds)
    }

    @Test
    fun legacy_adoption_requires_the_account_scope_to_agree() {
        val candidate = plan(
            sources = listOf(record(accountScope = "alice")),
            locals = listOf(local(serverId = "srv-legacy", accountScope = "bob")),
        ).items.single()
        assertEquals(SourceSyncAction.CREATE, candidate.action)
        assertEquals(SourceSyncReasons.NEW_SOURCE, candidate.reason)
    }

    @Test
    fun legacy_adoption_requires_the_normalised_address_to_agree() {
        val candidate = plan(
            sources = listOf(record()),
            locals = listOf(local(serverId = "srv-legacy", url = "http://nas:9096/emby")),
        ).items.single()
        assertEquals(SourceSyncAction.CREATE, candidate.action)
        assertEquals(SourceSyncReasons.NEW_SOURCE, candidate.reason)
    }

    @Test
    fun tombstone_without_a_local_source_is_no_change() {
        val candidate = plan(
            deletions = listOf(SourceTombstone(syncSourceId = "local-gone", deletedAtEpochMs = 5L, recordRevision = 4L)),
        ).items.single()
        assertEquals(SourceSyncAction.NO_CHANGE, candidate.action)
        assertEquals(SourceSyncReasons.NO_LOCAL_SOURCE, candidate.reason)
        assertFalse(candidate.requiresUserApproval)
        assertEquals(4L, candidate.remoteRevision)
    }

    @Test
    fun tombstone_deletes_the_matching_local_source_only_with_approval() {
        val candidate = plan(
            deletions = listOf(SourceTombstone(syncSourceId = "local-1", deletedAtEpochMs = 5L, recordRevision = 5L)),
            locals = listOf(local(serverId = "srv-9", syncSourceId = "local-1", recordRevision = 5L)),
        ).items.single()
        assertEquals(SourceSyncAction.DELETE, candidate.action)
        assertEquals(SourceSyncReasons.TOMBSTONE, candidate.reason)
        assertEquals("srv-9", candidate.serverId)
        assertEquals(5L, candidate.localRevision)
        assertTrue(candidate.requiresUserApproval)
    }

    @Test
    fun a_stale_tombstone_does_not_propose_deleting_live_data() {
        val candidate = plan(
            deletions = listOf(SourceTombstone(syncSourceId = "local-1", deletedAtEpochMs = 5L, recordRevision = 4L)),
            locals = listOf(local(serverId = "srv-9", syncSourceId = "local-1", recordRevision = 5L)),
        ).items.single()
        assertEquals(SourceSyncAction.IGNORE_STALE, candidate.action)
        assertEquals(SourceSyncReasons.STALE_REVISION, candidate.reason)
        assertFalse(candidate.requiresUserApproval)
    }

    @Test
    fun a_tombstone_matching_two_local_sources_is_a_conflict() {
        val candidate = plan(
            deletions = listOf(SourceTombstone(syncSourceId = "local-1", deletedAtEpochMs = 5L, recordRevision = 5L)),
            locals = listOf(
                local(serverId = "srv-1", syncSourceId = "local-1", recordRevision = 1L),
                local(serverId = "srv-2", syncSourceId = "local-1", recordRevision = 1L),
            ),
        ).items.single()
        assertEquals(SourceSyncAction.CONFLICT, candidate.action)
        assertEquals(SourceSyncReasons.DUPLICATE_LOCAL_SYNC_ID, candidate.reason)
    }

    @Test
    fun primary_endpoint_prefers_the_marked_one_and_the_smallest_sort_order() {
        val candidate = plan(
            sources = listOf(
                record(
                    endpoints = listOf(
                        SourceEndpoint(url = "http://a:1/x", isPrimary = false, sortOrder = 5),
                        SourceEndpoint(url = "http://b:2/y", isPrimary = true, sortOrder = 2),
                        SourceEndpoint(url = "http://c:3/z", isPrimary = true, sortOrder = 1),
                    ),
                ),
            ),
        ).items.single()
        assertEquals("http://c:3/z", candidate.normalizedUrl)
    }

    @Test
    fun without_a_marked_primary_the_smallest_sort_order_wins() {
        val candidate = plan(
            sources = listOf(
                record(
                    endpoints = listOf(
                        SourceEndpoint(url = "http://a:1/x", sortOrder = 5),
                        SourceEndpoint(url = "http://b:2/y", sortOrder = 2),
                    ),
                ),
            ),
        ).items.single()
        assertEquals("http://b:2/y", candidate.normalizedUrl)
    }

    @Test
    fun sync_id_matching_ignores_case_and_surrounding_whitespace() {
        val candidate = plan(
            sources = listOf(record(syncSourceId = "local-1", recordRevision = 3L)),
            locals = listOf(local(serverId = "srv-9", syncSourceId = "  LOCAL-1 ", recordRevision = 2L)),
        ).items.single()
        assertEquals(SourceSyncAction.UPDATE, candidate.action)
    }

    @Test
    fun wall_clock_values_are_never_compared() {
        // created/deleted 时间戳取极端值，动作一律不因时间而变：只有 recordRevision 参与排序。
        val delete = plan(
            deletions = listOf(SourceTombstone(syncSourceId = "local-1", deletedAtEpochMs = 0L, recordRevision = 0L)),
            locals = listOf(local(serverId = "srv-9", syncSourceId = "local-1", recordRevision = 0L)),
            createdAtEpochMs = 9_000_000_000_000L,
        ).items.single()
        assertEquals(SourceSyncAction.DELETE, delete.action)

        val noChange = plan(
            sources = listOf(record(recordRevision = 3L)),
            locals = listOf(local(serverId = "srv-9", syncSourceId = "local-1", recordRevision = 3L)),
            createdAtEpochMs = 0L,
        ).items.single()
        assertEquals(SourceSyncAction.NO_CHANGE, noChange.action)
    }

    @Test
    fun sources_are_planned_before_deletions() {
        val plan = plan(
            sources = listOf(record(syncSourceId = "local-1")),
            deletions = listOf(SourceTombstone(syncSourceId = "local-2")),
        )
        assertEquals(2, plan.items.size)
        assertEquals("local-1", plan.items[0].syncSourceId)
        assertEquals("local-2", plan.items[1].syncSourceId)
    }

    @Test
    fun plan_convenience_views_are_consistent() {
        val plan = plan(
            sources = listOf(
                record(syncSourceId = "local-1"),
                record(syncSourceId = "local-2", sourceType = "quantum-nas"),
            ),
            deletions = listOf(SourceTombstone(syncSourceId = "local-3")),
        )
        assertEquals(1, plan.countOf(SourceSyncAction.CREATE))
        assertEquals(1, plan.countOf(SourceSyncAction.UNSUPPORTED))
        assertEquals(1, plan.countOf(SourceSyncAction.NO_CHANGE))
        assertEquals(1, plan.effective.size)
        assertEquals(0, plan.conflicts.size)
        assertEquals(0, plan.requiringApproval.size)
    }

    @Test
    fun summary_buckets_match_the_cli_meaning() {
        val candidates = listOf(
            SourceSyncCandidate(action = SourceSyncAction.CREATE),
            SourceSyncCandidate(action = SourceSyncAction.CREATE),
            SourceSyncCandidate(action = SourceSyncAction.UPDATE),
            SourceSyncCandidate(action = SourceSyncAction.DELETE, requiresUserApproval = true),
            SourceSyncCandidate(action = SourceSyncAction.UNSUPPORTED, reason = SourceSyncReasons.UNKNOWN_SOURCE_TYPE),
            SourceSyncCandidate(action = SourceSyncAction.NO_CHANGE),
            SourceSyncCandidate(action = SourceSyncAction.IGNORE_STALE),
            SourceSyncCandidate(
                action = SourceSyncAction.CONFLICT,
                reason = SourceSyncReasons.AMBIGUOUS_LEGACY_IDENTITY,
                requiresUserApproval = true,
            ),
        )
        val summary = SourceSyncSummary.fromCandidates(candidates)
        assertEquals(8, summary.total)
        assertEquals(2, summary.create)
        assertEquals(1, summary.update)
        assertEquals(3, summary.apply)
        assertEquals(1, summary.delete)
        assertEquals(1, summary.unsupported)
        assertEquals(2, summary.unchanged)
        assertEquals(2, summary.ignored)
        assertEquals(1, summary.conflict)
        assertEquals(1, summary.ambiguous)
        assertEquals(2, summary.requiresConfirmation)
        assertTrue(summary.hasChanges)
    }

    @Test
    fun summary_of_an_all_quiet_plan_has_no_changes() {
        val summary = SourceSyncSummary.fromCandidates(
            listOf(
                SourceSyncCandidate(action = SourceSyncAction.NO_CHANGE),
                SourceSyncCandidate(action = SourceSyncAction.IGNORE_STALE),
            ),
        )
        assertFalse(summary.hasChanges)
        assertEquals(2, summary.ignored)
        assertEquals(0, summary.apply)
    }
}
