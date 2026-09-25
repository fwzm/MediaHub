package com.mediahub.feature.settings.backup

import androidx.room.Room
import com.mediahub.core.common.AppDispatchers
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.common.backup.BackupFileFormat
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.prefs.UserPreferencesStore
import com.mediahub.model.*
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real encrypted files, persisted Room, DataStore and journal; object recreation is not an OS kill test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun server(id: String, address: String = "https://$id.example") = MediaServer(id, id, ServerType.EMBY,
        createdAtEpochMs = 123, endpoints = listOf(ServerEndpoint("$id-endpoint", id, "primary", address, true)))
    private fun images() = RestoreImages(
        BackupSnapshot(listOf(server("local")), listOf(PlaybackProgress("local", "item", 2, 3, true, 4,
            posterUrl = "https://private.example?token=FAKE-PROTECTED-SENTINEL"))),
        BackupSnapshot(listOf(server("incoming")), emptyList()), UserPreferences(), UserPreferences(subtitleSizeSp = 42),
    )

    @Test fun `encrypted image survives store recreation without exposing local excluded fields`() {
        val id = "restore-${UUID.randomUUID()}"
        val secret = key()
        val expected = images()
        RestoreSnapshotStore(temporary.root, secret).save(id, expected)
        val raw = File(temporary.root, "$id.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("FAKE-PROTECTED-SENTINEL"))
        assertFalse(raw.contains("https://private.example"))
        assertEquals(expected, RestoreSnapshotStore(temporary.root, secret).read(id))
    }

    @Test fun `damaged ciphertext and wrong key cannot yield a usable snapshot`() {
        val id = "restore-${UUID.randomUUID()}"
        val secret = key()
        val store = RestoreSnapshotStore(temporary.root, secret)
        store.save(id, images())
        assertTrue(runCatching { RestoreSnapshotStore(temporary.root, key()).read(id) }.isFailure)
        val file = File(temporary.root, "$id.bin")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(runCatching { RestoreSnapshotStore(temporary.root, secret).read(id) }.isFailure)
    }

    @Test fun `private codec roundtrips every persistent field and nullable defaults`() {
        val endpoint = ServerEndpoint("endpoint-id", "local", "endpoint name", "https://local.example", true, false, 7,
            1, "local diagnostic", 2, 3, 4, 5.25, "h2", false, 206)
        val server = MediaServer("local", "name", ServerType.JELLYFIN, "user", "note", "icon", true, 8, 9, 10, "error", listOf(endpoint))
        val progress = PlaybackProgress("local", "item", 11, 12, false, 13, mode = PlaybackMode.DIRECT_STREAM,
            itemTitle = "title", posterUrl = "https://image.example?token=FAKE", itemType = MediaType.EPISODE)
        val prefs = UserPreferences(PlaybackEngineMode.MPV, 1.5f, 24, false, false, false, 14,
            true, false, false, SubtitleStyle(1, 2, 2, 3, 1.2f, 0.2f, false),
            PlayerGestures(false, true, 15, true, 20, false, 0.1f, 6f, false, 3f))
        val images = RestoreImages(BackupSnapshot(listOf(server), listOf(progress)),
            BackupSnapshot(listOf(this.server("empty")), listOf(PlaybackProgress("empty", "nulls", 0, 0, true, 0))),
            prefs, UserPreferences(), BackupDtos.RestorePlanRecord("MERGE", restoredProgressCount = 1))
        assertEquals(images, RestoreImageCodec.decode(RestoreImageCodec.encode(images)))
        assertNull(RestoreImageCodec.decode(RestoreImageCodec.encode(images)).after.progress.single().posterUrl)
        assertNull(RestoreImageCodec.decode(RestoreImageCodec.encode(images)).afterPreferences.maxBitrateBps)
    }

    @Test fun `snapshot reference cannot escape its private directory`() {
        val store = RestoreSnapshotStore(temporary.root, key())
        assertTrue(runCatching { store.save("../escaped", images()) }.isFailure)
        assertFalse(File(temporary.root.parentFile, "escaped.bin").exists())
    }

    @Test fun `new repository resumes every durable forward phase using real persisted stores`() = runBlocking {
        for (phase in listOf(RestoreJournal.Phase.PREPARING, RestoreJournal.Phase.DB_WRITTEN, RestoreJournal.Phase.PREFERENCES_APPLIED)) {
            exerciseRestart(phase, rollback = false)
        }
    }

    @Test fun `new repository resumes rollback and clears inserted data with exact local metadata`() = runBlocking {
        exerciseRestart(RestoreJournal.Phase.ROLLING_BACK, rollback = true)
    }

    private suspend fun exerciseRestart(phase: RestoreJournal.Phase, rollback: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mediahub_restore_journal", 0).edit().clear().commit()
        val directory = temporary.newFolder()
        // Robolectric's default data directory includes the complete test name. A real WAL database
        // can exceed Windows path limits there; keep the persisted database in this short test root.
        val databaseFile = File(directory, "restore.db")
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, databaseFile.absolutePath)
            .allowMainThreadQueries().build()
        var db = open()
        val secret = key()
        val dispatchers = AppDispatchers(io = Dispatchers.IO)
        val prefs = UserPreferencesStore(context)
        prefs.update { UserPreferences(maxBitrateBps = null) }
        val store = RestoreSnapshotStore(directory, secret)
        val firstJournal = SharedPrefsRestoreJournal(context, dispatchers)
        val invalidated = mutableListOf<String>()
        val invalidator = RestoreLoginInvalidator { invalidated += it }
        try {
            val source = ProductionBackupDataSource(db)
            val local = server("local").copy(icon = "icon", lastConnectedAtEpochMs = 789)
            source.applyRestorePlan(RestorePlan("seed", BackupDtos.RestorePlanRecord("MERGE", overwriteServerIds = listOf("local")),
                listOf(local), listOf(PlaybackProgress("local", "old", 2, 3, true, 4, posterUrl = "https://image.example?token=FAKE-SENTINEL")), null))
            val repo = BackupRepository(source, prefs, firstJournal, invalidator, dispatchers, store)
            val payload = BackupDtos.BackupPayload(
                BackupFileFormat.Manifest(1, 1, "test", 0, listOf("servers", "progress", "preferences"), mapOf("servers" to 2, "progress" to 0, "preferences" to 1)),
                listOf("local", "new").map { id -> BackupDtos.ServerDto(id, id, "EMBY", endpoints = listOf(BackupDtos.EndpointDto("primary", "https://changed-$id.example", true, true, 0))) },
                emptyList(), BackupDtos.PreferencesDto(subtitleSizeSp = 42, maxBitrateBps = 1_000_000),
            )
            val validated = (repo.prepareRestore(authenticatedTestBytes(payload, "test".toCharArray()), "test".toCharArray()) as PrepareResult.Prepared).validated
            val frozen = repo.buildPreview(validated, RestoreStrategy.REPLACE_SELECTED).frozenPlan!!
            store.save(frozen.plan.planId, frozen.images)
            firstJournal.begin(RestoreJournal.ActiveEntry(frozen.plan.planId, RestoreJournal.Phase.PREPARING,
                BackupDtos.encodePayload(validated.payload), BackupDtos.encodePlanRecord(frozen.plan.record), "", true, 0,
                protectiveSnapshotRef = frozen.plan.planId))
            if (phase != RestoreJournal.Phase.PREPARING) source.applyRestorePlan(frozen.plan)
            if (phase == RestoreJournal.Phase.PREFERENCES_APPLIED || rollback) prefs.update { frozen.images.afterPreferences }
            firstJournal.markPhase(frozen.plan.planId, phase)
            // Close the actual SQLite connection. The replacement components read the persisted inputs.
            assertTrue("The restart probe must have a real persisted database", databaseFile.isFile)
            db.close()
            assertTrue(databaseFile.isFile)
            db = open()
            val reopenedSource = ProductionBackupDataSource(db)
            val reopenedJournal = SharedPrefsRestoreJournal(context, dispatchers)
            val reopened = BackupRepository(reopenedSource, UserPreferencesStore(context), reopenedJournal,
                invalidator, dispatchers, RestoreSnapshotStore(directory, secret))
            val outcome = reopened.recoverInterruptedRestore()
            assertTrue("phase=$phase outcome=$outcome", if (rollback) outcome is RecoveryOutcome.RolledBack else outcome is RecoveryOutcome.Continued)
            val expected = if (rollback) frozen.images.before else frozen.images.after
            assertTrue(expected.sameData(reopenedSource.readSnapshot()))
            assertEquals(if (rollback) frozen.images.beforePreferences else frozen.images.afterPreferences, prefs.flow.first())
            assertNull(reopenedJournal.read())
            assertEquals(RecoveryOutcome.NothingToRecover, reopened.recoverInterruptedRestore())
            assertFalse(File(directory, "${frozen.plan.planId}.bin").exists())
            if (rollback) {
                assertFalse(reopenedSource.readSnapshot().servers.any { it.id == "new" })
                assertNull(prefs.flow.first().maxBitrateBps)
            } else assertEquals(listOf("local", "new"), invalidated)
        } finally {
            db.close()
            context.deleteDatabase(databaseFile.absolutePath)
        }
    }

    @Test fun `unpublished atomic file is a failed save before restore may begin`() {
        val id = "restore-${UUID.randomUUID()}"
        val blocker = File(temporary.root, "$id.bin").apply { mkdir() }
        val sentinel = File(blocker, "keep").apply { writeText("preserve") }
        val outcome = runCatching { RestoreSnapshotStore(temporary.root, key()).save(id, images()) }
        assertTrue("AtomicFile may silently fail rename; save must fail closed", outcome.isFailure)
        assertEquals("preserve", sentinel.readText())
    }
}
