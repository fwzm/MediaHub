package com.mediahub.app.backup

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediahub.core.common.AppDispatchers
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.common.backup.BackupFileFormat
import com.mediahub.core.common.backup.BackupSerializer
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.entity.AccountEntity
import com.mediahub.core.database.prefs.UserPreferencesStore
import com.mediahub.core.database.repository.AccountRepository
import com.mediahub.core.logging.LogBuffer
import com.mediahub.core.logging.MemoryLogger
import com.mediahub.core.security.*
import com.mediahub.feature.settings.backup.*
import com.mediahub.model.*
import com.mediahub.provider.api.CompositeSessionStoreCleaner
import com.mediahub.provider.emby.session.*
import com.mediahub.provider.jellyfin.session.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit destructive probe, run ONLY by method on a disposable emulator:
 * -e backupAcceptance isolated -e checkpoint PREPARING -e class ...#seedAndTerminate
 * then a NEW am instrument invocation selecting #recoverInNewProcess with the same checkpoint.
 * The first invocation must crash; only the second is a passing test. No sleeps or fake journal.
 */
@RunWith(AndroidJUnit4::class)
class RestoreProcessDeathTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = context.getSharedPreferences("agent_b_restore_probe", Context.MODE_PRIVATE)
    private val checkpoint get() = requireNotNull(args.getString("checkpoint"))
    private val dispatchers = AppDispatchers()
    private val oldPreferences = UserPreferences(defaultPlaybackSpeed = 1.25f, subtitleSizeSp = 22, maxBitrateBps = null)
    private val newPreferences = UserPreferences(defaultPlaybackSpeed = 1.75f, subtitleSizeSp = 26, maxBitrateBps = 800_000)

    private fun requireIsolated() {
        check(args.getString("backupAcceptance") == "isolated") { "Requires an explicitly disposable emulator" }
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) { "Never run this probe on a physical device" }
        check(checkpoint in setOf("SNAPSHOT", "PREPARING", "INVALIDATED", "DB_BEFORE_MARK", "DB_WRITTEN", "PREFERENCES_APPLIED", "ROLLING_BACK", "COMPLETED"))
    }

    private fun database() = Room.databaseBuilder(context, AppDatabase::class.java, "agent_b_restore_probe.db").build()
    private fun tokens() = TokenStore(KeystoreSecretStorage(context, MemoryLogger(LogBuffer())))
    private fun vault() = CredentialVault(KeystoreSecretStorage(context, MemoryLogger(LogBuffer())))
    private fun emby() = EmbySessionStore(EmbySessionStore.SharedPrefsStorage(context))
    private fun jellyfin() = JellyfinSessionStore(JellyfinSessionStore.SharedPrefsStorage(context))
    private fun invalidator(db: AppDatabase) = TokenSessionLoginInvalidator(tokens(), CompositeSessionStoreCleaner(setOf(
        EmbySessionCleaner(EmbySessionStore.SharedPrefsStorage(context)),
        JellyfinSessionCleaner(JellyfinSessionStore.SharedPrefsStorage(context)),
    )), vault(), AccountRepository(db))
    private fun server(id: String, url: String) = MediaServer(id = id, name = "Task fixture $id", type = ServerType.EMBY,
        username = "fixture-user", createdAtEpochMs = 100,
        endpoints = listOf(ServerEndpoint("$id-main", id, "Primary", url, isPrimary = true)))
    private fun oldServers() = listOf(server("probe-current", "https://old.example"), server("probe-untouched", "https://untouched.example"))
    private fun progress(id: String, item: String, position: Long) = PlaybackProgress(id, item, position, 60_000, true, 123,
        itemTitle = "Task fixture", itemType = MediaType.MOVIE)
    private fun oldProgress() = listOf(progress("probe-current", "old-only", 1000), progress("probe-untouched", "kept", 2000))

    private fun dieAt(stage: String, ref: String? = null) {
        if (checkpoint != stage) return
        check(marker.edit().putString("checkpoint", stage).putInt("terminatedPid", Process.myPid())
            .putString("snapshotRef", ref).commit())
        Process.killProcess(Process.myPid())
        error("Process termination did not occur")
    }

    @Test fun seedAndTerminate() = runBlocking {
        requireIsolated()
        val db = database()
        try {
            val data = ProductionBackupDataSource(db)
            val prefs = UserPreferencesStore(context)
            val journal = SharedPrefsRestoreJournal(context, dispatchers)
            check(journal.read() == null) { "A previous probe is unresolved" }
            data.applyRestorePlan(RestorePlan("seed", BackupDtos.RestorePlanRecord("ROLLBACK"), oldServers(), oldProgress(), null))
            prefs.update { oldPreferences }
            for (id in listOf("probe-current", "probe-untouched", "probe-added")) {
                tokens().saveTokens(id, StoredToken("fixture-token-$id"))
                vault().save(id, CredentialVault.CredentialKind.PASSWORD, "fixture-password")
                emby().save(EmbySession(id, "fixture-remote", "fixture-user-id", "fixture-user"))
                jellyfin().save(JellyfinSession(id, "fixture-remote", "fixture-user-id", "fixture-user"))
                if (id != "probe-added") db.accountDao().upsert(AccountEntity(id, id, "fixture-user-id", "Fixture", "AUTHENTICATED"))
            }
            // Fixture writes use the existing asynchronous save API; establish a durable starting point.
            for (name in listOf("mediahub_secret_store", "mediahub_emby_sessions", "mediahub_jellyfin_sessions")) {
                check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit())
            }
            val snapshotStore = RestoreSnapshotStore(context)
            val crashingSnapshot = object : RestoreSnapshotStorage by snapshotStore {
                override fun save(id: String, images: RestoreImages) { snapshotStore.save(id, images); dieAt("SNAPSHOT", id) }
            }
            val crashingJournal = object : RestoreJournal by journal {
                override suspend fun begin(entry: RestoreJournal.ActiveEntry) { journal.begin(entry); dieAt("PREPARING", entry.protectiveSnapshotRef) }
                override suspend fun markPhase(planId: String, phase: RestoreJournal.Phase) {
                    if (checkpoint == "ROLLING_BACK" && phase == RestoreJournal.Phase.COMPLETED) error("Injected completion persistence failure")
                    journal.markPhase(planId, phase)
                    dieAt(phase.name)
                }
            }
            val loginInvalidator = invalidator(db)
            val crashingInvalidator = object : RestoreLoginInvalidator by loginInvalidator {
                override suspend fun invalidate(serverId: String) { loginInvalidator.invalidate(serverId); dieAt("INVALIDATED") }
            }
            val crashingData = object : BackupDataSource by data {
                override suspend fun applyRestorePlan(plan: RestorePlan) { data.applyRestorePlan(plan); dieAt("DB_BEFORE_MARK") }
            }
            val repo = BackupRepository(crashingData, prefs, crashingJournal, crashingInvalidator, dispatchers, crashingSnapshot)
            val servers = listOf(server("probe-current", "https://changed.example"), server("probe-added", "https://added.example"))
            val incoming = (1..35).map { progress("probe-current", "item-$it", it * 1000L) } + progress("probe-added", "new", 3000)
            val payload = BackupDtos.BackupPayload(
                BackupFileFormat.Manifest(1, 1, "instrumented-fixture", 1, listOf("servers", "progress", "preferences"),
                    mapOf("servers" to 2, "progress" to incoming.size, "preferences" to 1)),
                servers.map { s -> BackupDtos.ServerDto(s.id, s.name, s.type.name, username = s.username,
                    endpoints = s.endpoints.map { BackupDtos.EndpointDto(it.name, it.url, it.isPrimary, it.enabled, it.sortOrder) }) },
                incoming.map { BackupDtos.ProgressDto(it.serverId, it.itemId, it.positionMs, it.durationMs, it.isPaused, it.updatedAtEpochMs,
                    itemTitle = it.itemTitle, itemType = it.itemType?.name) },
                BackupDtos.PreferencesDto(defaultPlaybackSpeed = newPreferences.defaultPlaybackSpeed,
                    subtitleSizeSp = newPreferences.subtitleSizeSp, maxBitrateBps = newPreferences.maxBitrateBps),
            )
            val bytes = BackupSerializer.export(payload, "fixture-password".toCharArray())
            val prepared = repo.prepareRestore(bytes, "fixture-password".toCharArray()) as PrepareResult.Prepared
            repo.restore(prepared.validated, RestoreStrategy.REPLACE_SELECTED, true,
                repo.buildPreview(prepared.validated, RestoreStrategy.REPLACE_SELECTED))
            fail("Probe failed to terminate at the selected stage")
        } finally { db.close() }
    }

    @Test fun recoverInNewProcess() = runBlocking {
        requireIsolated()
        assertEquals(checkpoint, marker.getString("checkpoint", null))
        assertNotEquals("Must verify a new OS process, not a reconstructed fake", marker.getInt("terminatedPid", -1), Process.myPid())
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("checkpoint", checkpoint)
            putInt("terminatedPid", marker.getInt("terminatedPid", -1))
            putInt("recoveryPid", Process.myPid())
        })
        val db = database()
        try {
            val data = ProductionBackupDataSource(db)
            val prefs = UserPreferencesStore(context)
            val journal = SharedPrefsRestoreJournal(context, dispatchers)
            val snapshotStore = RestoreSnapshotStore(context)
            val repo = BackupRepository(data, prefs, journal, invalidator(db), dispatchers, snapshotStore)
            val outcome = repo.recoverInterruptedRestore()
            val beforeExpected = checkpoint in setOf("SNAPSHOT", "ROLLING_BACK")
            if (checkpoint == "SNAPSHOT") assertTrue(outcome is RecoveryOutcome.NothingToRecover)
            else if (checkpoint == "ROLLING_BACK") assertTrue(outcome is RecoveryOutcome.RolledBack)
            else if (checkpoint == "COMPLETED") assertTrue(outcome is RecoveryOutcome.CompletedEarlier)
            else assertTrue(outcome.toString(), outcome is RecoveryOutcome.Continued)
            val snapshot = data.readSnapshot()
            if (beforeExpected) {
                assertTrue(BackupSnapshot(oldServers(), oldProgress()).sameData(snapshot))
                assertEquals(oldPreferences, prefs.flow.first())
            } else {
                assertEquals(setOf("probe-current", "probe-untouched", "probe-added"), snapshot.servers.map { it.id }.toSet())
                assertEquals("https://changed.example", snapshot.servers.single { it.id == "probe-current" }.endpoints.single().url)
                assertEquals(37, snapshot.progress.size)
                assertFalse(snapshot.progress.any { it.serverId == "probe-current" && it.itemId == "old-only" })
                assertTrue(snapshot.progress.contains(progress("probe-untouched", "kept", 2000)))
                assertEquals(newPreferences, prefs.flow.first())
            }
            assertTrue(snapshot.servers.count { it.isDefault } <= 1)
            assertNotNull(tokens().readTokens("probe-untouched"))
            assertNotNull(db.accountDao().getForServer("probe-untouched"))
            if (checkpoint != "SNAPSHOT") {
                assertNull(tokens().readTokens("probe-current"))
                assertFalse(vault().contains("probe-current", CredentialVault.CredentialKind.PASSWORD))
                assertNull(emby().read("probe-current"))
                assertNull(jellyfin().read("probe-current"))
                assertNull(db.accountDao().getForServer("probe-current"))
                assertNull("Orphan auth must not attach to an imported new ID", tokens().readTokens("probe-added"))
                assertFalse(vault().contains("probe-added", CredentialVault.CredentialKind.PASSWORD))
                assertNull(emby().read("probe-added"))
                assertNull(jellyfin().read("probe-added"))
            }
            assertNull(journal.read())
            assertTrue(repo.recoverInterruptedRestore() is RecoveryOutcome.NothingToRecover)
            assertTrue(snapshot.sameData(data.readSnapshot()))
            if (checkpoint == "SNAPSHOT") marker.getString("snapshotRef", null)?.let(snapshotStore::delete)
        } finally { db.close() }
    }

    /** Run after PREPARING seed killed the process and shell corrupted this disposable app's journal XML. */
    @Test fun corruptedDiskJournalBlocksRecoveryAndNewRestore() = runBlocking {
        requireIsolated()
        assertEquals("PREPARING", checkpoint)
        assertNotEquals(marker.getInt("terminatedPid", -1), Process.myPid())
        val db = database()
        val prefs = UserPreferencesStore(context)
        val snapshotStore = RestoreSnapshotStore(context)
        try {
            val data = ProductionBackupDataSource(db)
            val repo = BackupRepository(data, prefs, SharedPrefsRestoreJournal(context, dispatchers), invalidator(db), dispatchers, snapshotStore)
            val before = data.readSnapshot()
            assertTrue(BackupSnapshot(oldServers(), oldProgress()).sameData(before))
            assertTrue(repo.recoverInterruptedRestore() is RecoveryOutcome.NeedsAttention)
            val bytes = repo.exportBackup("fixture-password".toCharArray(), "instrumented-fixture")
            val prepared = repo.prepareRestore(bytes, "fixture-password".toCharArray()) as PrepareResult.Prepared
            val preview = repo.buildPreview(prepared.validated, RestoreStrategy.MERGE)
            try {
                repo.restore(prepared.validated, RestoreStrategy.MERGE, false, preview)
                fail("Corrupt disk journal must block a new restore")
            } catch (_: RestoreFailedException) { }
            assertTrue(before.sameData(data.readSnapshot()))
            assertEquals(oldPreferences, prefs.flow.first())
            assertNotNull(tokens().readTokens("probe-current"))
        } finally {
            // Explicit cleanup is confined to the disposable emulator required above.
            check(context.deleteSharedPreferences("mediahub_restore_journal"))
            marker.getString("snapshotRef", null)?.let(snapshotStore::delete)
            db.close()
        }
    }
}
