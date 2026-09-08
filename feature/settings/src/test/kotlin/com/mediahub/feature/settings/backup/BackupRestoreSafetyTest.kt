package com.mediahub.feature.settings.backup

import androidx.room.Room
import com.mediahub.core.common.AppDispatchers
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.common.backup.BackupFileFormat
import com.mediahub.core.common.backup.BackupSerializer
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.model.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the production Room adapter, with failure boundaries around external stores. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreSafetyTest {
    private lateinit var db: AppDatabase
    private lateinit var data: ProductionBackupDataSource
    private val prefs = object : UserPreferencesRepository {
        override val flow = MutableStateFlow(UserPreferences())
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            flow.value = transform(flow.value)
        }
    }
    private class Journal : RestoreJournal {
        var entry: RestoreJournal.ActiveEntry? = null
        var failComplete = false
        var failClear = false
        override suspend fun begin(entry: RestoreJournal.ActiveEntry) { this.entry = entry }
        override suspend fun read() = entry
        override suspend fun markPhase(planId: String, phase: RestoreJournal.Phase) {
            if (phase == RestoreJournal.Phase.COMPLETED && failComplete) error("completion write failed")
            entry = entry!!.copy(phase = phase)
        }
        override suspend fun markFailed(planId: String, cause: String) { entry = entry!!.copy(lastError = cause) }
        override suspend fun clear(planId: String) { if (failClear) error("clear failed"); entry = null }
    }
    private val journal = Journal()
    private val snapshots = MemoryRestoreSnapshotStorage()
    private val cleared = mutableListOf<String>()
    private var invalidationFailure: Exception? = null
    private fun repository(target: BackupDataSource = data) = BackupRepository(target, prefs, journal, object : RestoreLoginInvalidator {
        override suspend fun invalidate(serverId: String) {
            invalidationFailure?.let { throw it }
            cleared += serverId
        }
    }, AppDispatchers(io = Dispatchers.IO), snapshots)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        data = ProductionBackupDataSource(db)
    }
    @After fun tearDown() { db.close() }
    private fun server(id: String, url: String = "https://$id.example", isDefault: Boolean = false) = MediaServer(
        id = id, name = id, type = ServerType.EMBY, isDefault = isDefault, createdAtEpochMs = 1,
        endpoints = listOf(ServerEndpoint(id = "${id}-ep", serverId = id, name = "Primary", url = url, isPrimary = true)),
    )
    private fun progress(id: String, item: String) = PlaybackProgress(
        serverId = id, itemId = item, positionMs = 100, durationMs = 1000, isPaused = true, updatedAtEpochMs = 10,
    )
    private fun payload(servers: List<MediaServer>, progress: List<PlaybackProgress> = emptyList(), preference: Int? = null) = BackupDtos.BackupPayload(
        manifest = BackupFileFormat.Manifest(1, 1, "test", 0, listOf("servers", "progress", "preferences"),
            mapOf("servers" to servers.size, "progress" to progress.size, "preferences" to if (preference == null) 0 else 1)),
        servers = servers.map { s -> BackupDtos.ServerDto(s.id, s.name, s.type.name, isDefault = s.isDefault,
            endpoints = s.endpoints.map { BackupDtos.EndpointDto(it.name, it.url, it.isPrimary, it.enabled, it.sortOrder) }) },
        progress = progress.map { BackupDtos.ProgressDto(it.serverId, it.itemId, it.positionMs, it.durationMs, it.isPaused, it.updatedAtEpochMs) },
        preferences = preference?.let { BackupDtos.PreferencesDto(subtitleSizeSp = it) },
    )
    private suspend fun seed(servers: List<MediaServer>, progress: List<PlaybackProgress> = emptyList()) {
        data.applyRestorePlan(RestorePlan("seed", BackupDtos.RestorePlanRecord("MERGE", overwriteServerIds = servers.map { it.id }), servers, progress, null))
    }
    private suspend fun prepare(repo: BackupRepository, payload: BackupDtos.BackupPayload): ValidatedRestore {
        val bytes = BackupSerializer.export(payload, "test".toCharArray(), testIterations = 10_000)
        return (repo.prepareRestore(bytes, "test".toCharArray()) as PrepareResult.Prepared).validated
    }

    @Test fun `merge imported endpoint identifiers cannot steal another sources local endpoint`() = runBlocking {
        val local = server("local").let { it.copy(endpoints = it.endpoints.map { endpoint -> endpoint.copy(id = "new_ep0") }) }
        seed(listOf(local), listOf(progress("local", "keep")))
        val repo = repository()
        val validated = prepare(repo, payload(listOf(server("new"))))
        val preview = repo.buildPreview(validated, RestoreStrategy.MERGE)
        repo.restore(validated, RestoreStrategy.MERGE, false, preview)
        val actual = data.readSnapshot()
        assertEquals("Importing a new source must retain the existing source's endpoint ownership",
            local, actual.servers.single { it.id == "local" })
        assertEquals(listOf(progress("local", "keep")), actual.progress)
        val incomingEndpoint = actual.servers.single { it.id == "new" }.endpoints.single()
        assertNotEquals("new_ep0", incomingEndpoint.id)
        assertEquals("new", incomingEndpoint.serverId)
        assertTrue(preview.frozenPlan!!.images.after.sameData(actual))
    }

    @Test fun `failed credential invalidation never overwrites source or progress`() = runBlocking {
        val original = server("a")
        seed(listOf(original), listOf(progress("a", "local")))
        val before = data.readSnapshot()
        val repo = repository()
        val validated = prepare(repo, payload(listOf(server("a", "https://changed.example")), listOf(progress("a", "incoming"))))
        invalidationFailure = IllegalStateException("storage unavailable")
        val outcome = runCatching { repo.restoreWithFreshPreview(validated, RestoreStrategy.REPLACE_SELECTED, true) }
        assertTrue("credential clear failure must abort", outcome.isFailure)
        assertEquals("source and progress remain untouched", before, data.readSnapshot())
        assertTrue(cleared.isEmpty())
    }

    @Test fun `credential cancellation propagates and cannot change source`() = runBlocking {
        seed(listOf(server("a")))
        val before = data.readSnapshot()
        val repo = repository()
        val validated = prepare(repo, payload(listOf(server("a", "https://changed.example"))))
        invalidationFailure = CancellationException("cancelled")
        val outcome = runCatching { repo.restoreWithFreshPreview(validated, RestoreStrategy.REPLACE_SELECTED, true) }
        assertTrue("cancellation must propagate", outcome.exceptionOrNull() is CancellationException)
        assertEquals(before, data.readSnapshot())
    }

    @Test fun `merge preserves local default when incoming new source is default`() = runBlocking {
        seed(listOf(server("local", isDefault = true)))
        val repo = repository()
        repo.restoreWithFreshPreview(prepare(repo, payload(listOf(server("incoming", isDefault = true)))), RestoreStrategy.MERGE, false)
        assertEquals(listOf("local"), data.readSnapshot().servers.filter { it.isDefault }.map { it.id })
    }

    @Test fun `replace removes obsolete selected progress and keeps unselected progress`() = runBlocking {
        seed(listOf(server("a"), server("other")), listOf(progress("a", "obsolete"), progress("other", "kept")))
        val repo = repository()
        repo.restoreWithFreshPreview(prepare(repo, payload(listOf(server("a")), listOf(progress("a", "new")))), RestoreStrategy.REPLACE_SELECTED, true)
        val records = data.readSnapshot().progress
        assertFalse(records.any { it.serverId == "a" && it.itemId == "obsolete" })
        assertTrue(records.any { it.serverId == "a" && it.itemId == "new" })
        assertTrue(records.any { it.serverId == "other" && it.itemId == "kept" })
    }

    @Test fun `rollback removes added rows and restores original preferences`() = runBlocking {
        seed(listOf(server("local")), listOf(progress("local", "kept")))
        val before = data.readSnapshot()
        val originalPrefs = prefs.flow.value
        val repo = repository()
        journal.failComplete = true
        val result = runCatching { repo.restoreWithFreshPreview(prepare(repo, payload(listOf(server("new")), listOf(progress("new", "new")), 42)), RestoreStrategy.REPLACE_SELECTED, true) }
        assertTrue(result.exceptionOrNull() is RestoreFailedException)
        assertEquals("added sources and progress must disappear", before, data.readSnapshot())
        assertEquals("DataStore preferences restored after failure", originalPrefs, prefs.flow.value)
    }

    @Test fun `preparing recovery clears old identity credentials before new address becomes visible`() = runBlocking {
        val local = server("a")
        seed(listOf(local))
        val repo = repository()
        val validated = prepare(repo, payload(listOf(server("a", "https://changed.example"))))
        val preview = repo.buildPreview(validated, RestoreStrategy.REPLACE_SELECTED)
        val frozen = preview.frozenPlan!!
        snapshots.save(frozen.plan.planId, frozen.images)
        journal.entry = RestoreJournal.ActiveEntry(frozen.plan.planId, RestoreJournal.Phase.PREPARING,
            BackupDtos.encodePayload(validated.payload), BackupDtos.encodePlanRecord(frozen.plan.record), "", false, 0,
            protectiveSnapshotRef = frozen.plan.planId)
        val outcome = repository().recoverInterruptedRestore()
        assertTrue(outcome is RecoveryOutcome.Continued)
        assertEquals("same cleanup required after process restart", listOf("a"), cleared)
        assertEquals("https://changed.example", data.readSnapshot().servers.single().endpoints.single().url)
    }

    @Test fun `preview baseline change blocks restore without writes or credential clearing`() = runBlocking {
        seed(listOf(server("a")))
        val repo = repository()
        val validated = prepare(repo, payload(listOf(server("a", "https://changed.example"))))
        val preview = repo.buildPreview(validated, RestoreStrategy.REPLACE_SELECTED)
        db.serverDao().markConnected("a", 123)
        val changed = data.readSnapshot()
        val result = runCatching { repo.restore(validated, RestoreStrategy.REPLACE_SELECTED, true, preview) }
        assertTrue(result.exceptionOrNull() is RestoreBaselineChangedException)
        assertEquals(changed, data.readSnapshot())
        assertTrue(cleared.isEmpty())
        assertNull(journal.entry)
    }

    @Test fun `Room baseline is rechecked inside write transaction`() = runBlocking {
        seed(listOf(server("a")))
        val before = data.readSnapshot()
        val plan = RestorePlan("attempt", BackupDtos.RestorePlanRecord("REPLACE_SELECTED", overwriteServerIds = listOf("a")),
            listOf(server("a", "https://changed.example")), emptyList(), null, before)
        db.playbackProgressDao().upsert(com.mediahub.core.database.entity.PlaybackProgressEntity("a", "late", 50, 100, true, 20))
        val changed = data.readSnapshot()
        assertTrue(runCatching { data.applyRestorePlan(plan) }.exceptionOrNull() is RestoreBaselineChangedException)
        assertEquals(changed, data.readSnapshot())
    }

    @Test fun `rollback preserves all local metadata and progress presentation fields`() = runBlocking {
        val local = server("a").copy(icon = "local-icon", createdAtEpochMs = 123, lastConnectedAtEpochMs = 456,
            endpoints = server("a").endpoints.map { it.copy(lastLatencyMs = 3, lastSupportsRange = true, lastMediaThroughputMbps = 20.5) })
        val record = progress("a", "local").copy(mode = com.mediahub.model.PlaybackMode.DIRECT_STREAM,
            posterUrl = "https://image.example/private?token=FAKE-SENTINEL", itemTitle = "local title")
        seed(listOf(local), listOf(record))
        val before = data.readSnapshot()
        journal.failComplete = true
        val repo = repository()
        val result = runCatching { repo.restoreWithFreshPreview(prepare(repo, payload(listOf(server("a")))), RestoreStrategy.REPLACE_SELECTED, true) }
        assertTrue(result.exceptionOrNull() is RestoreFailedException)
        assertEquals(before, data.readSnapshot())
        assertTrue("same identity retains login", cleared.isEmpty())
    }

    @Test fun `completion cleanup failure never rolls back completed data`() = runBlocking {
        seed(listOf(server("local")))
        journal.failClear = true
        val repo = repository()
        val prepared = prepare(repo, payload(listOf(server("new"))))
        val result = runCatching { repo.restoreWithFreshPreview(prepared, RestoreStrategy.MERGE, false) }
        assertTrue(result.exceptionOrNull() is RestoreFailedException)
        assertEquals(RestoreJournal.Phase.COMPLETED, journal.entry?.phase)
        assertTrue("completed restore is retained", data.readSnapshot().servers.any { it.id == "new" })
        assertTrue(repo.recoverInterruptedRestore() is RecoveryOutcome.NeedsAttention)
        assertTrue(data.readSnapshot().servers.any { it.id == "new" })
        journal.failClear = false
        assertEquals(RecoveryOutcome.CompletedEarlier, repo.recoverInterruptedRestore())
        assertNull(journal.entry)
    }

    @Test fun `recovery reports frozen merge writes rather than full database record count`() = runBlocking {
        seed(listOf(server("local")), listOf(progress("local", "kept-1"), progress("local", "kept-2")))
        val repo = repository()
        val validated = prepare(repo, payload(listOf(server("new")), listOf(progress("new", "incoming"))))
        val frozen = repo.buildPreview(validated, RestoreStrategy.MERGE).frozenPlan!!
        snapshots.save(frozen.plan.planId, frozen.images)
        journal.entry = RestoreJournal.ActiveEntry(frozen.plan.planId, RestoreJournal.Phase.PREPARING,
            BackupDtos.encodePayload(validated.payload), BackupDtos.encodePlanRecord(frozen.plan.record), "", false, 0,
            protectiveSnapshotRef = frozen.plan.planId)
        val outcome = repo.recoverInterruptedRestore() as RecoveryOutcome.Continued
        assertEquals(1, outcome.result.restoredProgress)
        assertEquals(3, data.readSnapshot().progress.size)
    }

    @Test fun `transaction baseline conflict preserves concurrent progress and permits a fresh preview`() = runBlocking {
        seed(listOf(server("local")))
        val changed = object : BackupDataSource by data {
            override suspend fun applyRestorePlan(plan: RestorePlan) {
                db.playbackProgressDao().upsert(com.mediahub.core.database.entity.PlaybackProgressEntity("local", "concurrent", 10, 100, true, 20))
                data.applyRestorePlan(plan)
            }
        }
        val repo = repository(changed)
        val validated = prepare(repo, payload(listOf(server("new"))))
        val failure = runCatching { repo.restoreWithFreshPreview(validated, RestoreStrategy.MERGE, false) }.exceptionOrNull()
        assertTrue(failure is RestoreBaselineChangedException)
        assertFalse(data.readSnapshot().servers.any { it.id == "new" })
        assertTrue(data.readSnapshot().progress.any { it.itemId == "concurrent" })
        assertNull("no partial database operation remains to recover", journal.entry)
        assertEquals(RecoveryOutcome.NothingToRecover, repo.recoverInterruptedRestore())
    }

    @Test fun `new source cannot inherit orphan credentials keyed by its imported id`() = runBlocking {
        val orphanAuth = mutableMapOf("incoming" to "FAKE-ORPHAN-TOKEN")
        val targets = mutableListOf<String>()
        val repo = BackupRepository(data, prefs, journal, RestoreLoginInvalidator { id -> targets += id; orphanAuth.remove(id) },
            AppDispatchers(io = Dispatchers.IO), snapshots)
        val result = repo.restoreWithFreshPreview(prepare(repo, payload(listOf(server("incoming")))), RestoreStrategy.MERGE, false)
        assertNull("new identity must not inherit credentials from a deleted source", orphanAuth["incoming"])
        assertEquals(listOf("incoming"), targets)
        assertEquals(1, result.loginsInvalidated)
    }

    @Test fun `rollback cannot carry after-identity credentials back to original address`() = runBlocking {
        seed(listOf(server("a")))
        val repo = repository()
        val validated = prepare(repo, payload(listOf(server("a", "https://changed.example"))))
        val frozen = repo.buildPreview(validated, RestoreStrategy.REPLACE_SELECTED).frozenPlan!!
        snapshots.save(frozen.plan.planId, frozen.images)
        data.applyRestorePlan(frozen.plan)
        journal.entry = RestoreJournal.ActiveEntry(frozen.plan.planId, RestoreJournal.Phase.ROLLING_BACK,
            BackupDtos.encodePayload(validated.payload), BackupDtos.encodePlanRecord(frozen.plan.record), "", false, 0,
            protectiveSnapshotRef = frozen.plan.planId)
        val afterAuth = mutableMapOf("a" to "FAKE-AFTER-IDENTITY-TOKEN")
        val recovering = BackupRepository(data, prefs, journal, RestoreLoginInvalidator { id -> afterAuth.remove(id) },
            AppDispatchers(io = Dispatchers.IO), snapshots)
        assertEquals(RecoveryOutcome.RolledBack, recovering.recoverInterruptedRestore())
        assertEquals("https://a.example", data.readSnapshot().servers.single().endpoints.single().url)
        assertNull("rollback changes identity too", afterAuth["a"])
    }
}
