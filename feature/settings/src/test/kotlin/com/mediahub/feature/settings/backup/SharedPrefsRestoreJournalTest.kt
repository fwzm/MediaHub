package com.mediahub.feature.settings.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import com.mediahub.core.common.AppDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the production journal against real SharedPreferences, with only commit faults injected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPrefsRestoreJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var journal: SharedPrefsRestoreJournal
    private val dispatchers = AppDispatchers(io = Dispatchers.IO)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        journal = SharedPrefsRestoreJournal(context, dispatchers)
    }

    @Test
    fun `reopened production journal retains complete entry and phases`() = runBlocking {
        val original = entry()
        journal.begin(original)
        assertEquals(original, SharedPrefsRestoreJournal(context, dispatchers).read())
        journal.markPhase(original.planId, RestoreJournal.Phase.DB_WRITTEN)
        assertEquals(RestoreJournal.Phase.DB_WRITTEN, SharedPrefsRestoreJournal(context, dispatchers).read()?.phase)
        journal.clear(original.planId)
        assertNull(SharedPrefsRestoreJournal(context, dispatchers).read())
    }

    @Test
    fun `encrypted snapshot reference and rollback intent survive reopening`() = runBlocking {
        val original = entry().copy(protectiveSnapshotJson = "", protectiveSnapshotRef = "private-snapshot-id")
        journal.begin(original)
        journal.markPhase(original.planId, RestoreJournal.Phase.ROLLING_BACK)
        val reopened = SharedPrefsRestoreJournal(context, dispatchers).read()
        assertEquals(original.copy(phase = RestoreJournal.Phase.ROLLING_BACK), reopened)
        assertEquals("", reopened?.protectiveSnapshotJson)
    }

    @Test
    fun `legacy eight field record remains visible for explicit recovery handling`() = runBlocking {
        val original = entry()
        journal.begin(original)
        val raw = checkNotNull(prefs.getString(KEY_ACTIVE, null)).substringBeforeLast("\u0001")
        check(prefs.edit().putString(KEY_ACTIVE, raw).commit())
        assertEquals(original, SharedPrefsRestoreJournal(context, dispatchers).read())
    }

    @Test
    fun `invalid lengths phases booleans and identities cannot become a valid record`() = runBlocking {
        journal.begin(entry())
        val valid = checkNotNull(prefs.getString(KEY_ACTIVE, null)).split("\u0001")
        val invalidRecords = listOf(
            0 to "",
            1 to "UNKNOWN",
            2 to "-1:{}",
            2 to "2147483647:{}",
            2 to "2:{}extra",
            5 to "maybe",
            6 to "-1",
        )
        for ((index, value) in invalidRecords) {
            val fields = valid.toMutableList().apply { set(index, value) }
            val raw = fields.joinToString("\u0001")
            check(prefs.edit().putString(KEY_ACTIVE, raw).commit())
            assertTrue(failure { journal.read() } is RestoreJournalCorruptedException)
            assertEquals(raw, prefs.getString(KEY_ACTIVE, null))
        }
    }

    @Test
    fun `corrupt persisted record fails closed without erasing evidence`() = runBlocking {
        val raw = "CORRUPT_FAKE_SECRET"
        check(prefs.edit().putString(KEY_ACTIVE, raw).commit())
        val observedFailure = failure { journal.read() }
        assertFalse(observedFailure.toString().contains(raw))
        assertEquals(raw, prefs.getString(KEY_ACTIVE, null))
        failure { journal.begin(entry()) }
        failure { journal.clear("p1") }
        assertEquals(raw, prefs.getString(KEY_ACTIVE, null))
    }

    @Test
    fun `trailing unvalidated field bytes are rejected as corruption`() = runBlocking {
        journal.begin(entry())
        val raw = checkNotNull(prefs.getString(KEY_ACTIVE, null)) + "UNVALIDATED_FAKE_SECRET"
        check(prefs.edit().putString(KEY_ACTIVE, raw).commit())
        failure { journal.read() }
        assertEquals(raw, prefs.getString(KEY_ACTIVE, null))
    }

    @Test
    fun `begin cannot replace an existing unresolved operation`() = runBlocking {
        val original = entry()
        journal.begin(original)
        failure { journal.begin(entry("other")) }
        assertEquals(original, journal.read())
    }

    @Test
    fun `phase failure and clear require matching active plan`() = runBlocking {
        val original = entry()
        journal.begin(original)
        failure { journal.markPhase("other", RestoreJournal.Phase.COMPLETED) }
        failure { journal.markFailed("other", "FAKE_SECRET") }
        failure { journal.clear("other") }
        assertEquals(original, journal.read())
    }

    @Test
    fun `phase failure and clear reject missing operation`() = runBlocking {
        failure { journal.markPhase("missing", RestoreJournal.Phase.COMPLETED) }
        failure { journal.markFailed("missing", "FAKE_SECRET") }
        failure { journal.clear("missing") }
        assertNull(journal.read())
        assertFalse(prefs.contains(KEY_ACTIVE))
    }

    @Test
    fun `failed begin commit is surfaced before data can be applied`() = runBlocking {
        val faulty = faultJournal { true }
        failure { faulty.begin(entry()) }
        assertNull(journal.read())
    }

    @Test
    fun `failed phase and error commits retain previous durable stage`() = runBlocking {
        val original = entry()
        journal.begin(original)
        val faulty = faultJournal { true }
        failure { faulty.markPhase(original.planId, RestoreJournal.Phase.DB_WRITTEN) }
        failure { faulty.markFailed(original.planId, "FAKE_SECRET") }
        assertEquals(original, journal.read())
    }

    @Test
    fun `failed clear commit retains pending recovery`() = runBlocking {
        val original = entry()
        journal.begin(original)
        failure { faultJournal { true }.clear(original.planId) }
        assertEquals(original, journal.read())
    }

    @Test
    fun `failed clear whose memory map changed never appears as missing recovery`() = runBlocking {
        journal.begin(entry())
        val faulty = faultJournal(applyBeforeFailure = true) { true }
        assertTrue(failure { faulty.clear("p1") } is RestoreJournalPersistenceException)
        // Real Android commit failures may already have changed the cache; the journal must distrust it.
        assertNull(prefs.getString(KEY_ACTIVE, null))
        assertTrue(failure { faulty.read() } is RestoreJournalPersistenceException)
        assertTrue(failure { faulty.begin(entry("other")) } is RestoreJournalPersistenceException)
    }

    @Test
    fun `failure journal never persists raw exception text`() = runBlocking {
        val secret = "Authorization: Bearer FAKE_SECRET"
        journal.begin(entry())
        journal.markFailed("p1", secret)
        val reopened = SharedPrefsRestoreJournal(context, dispatchers).read()
        assertNotNull(reopened?.lastError)
        assertFalse(reopened.toString().contains(secret))
        assertFalse(checkNotNull(prefs.getString(KEY_ACTIVE, null)).contains(secret))
    }

    @Test
    fun `two production instances cannot both begin the single journal slot`() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val readyA = CompletableDeferred<Unit>()
        val readyB = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            readyA.complete(Unit)
            start.await()
            runCatching { journal.begin(entry("first")) }.isSuccess
        }
        val second = async(Dispatchers.Default) {
            readyB.complete(Unit)
            start.await()
            runCatching { SharedPrefsRestoreJournal(context, dispatchers).begin(entry("second")) }.isSuccess
        }
        try {
            withTimeout(5_000) {
                readyA.await()
                readyB.await()
                start.complete(Unit)
                assertEquals(1, awaitAll(first, second).count { it })
                assertTrue(journal.read()?.planId in setOf("first", "second"))
            }
        } finally {
            start.complete(Unit)
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun `malformed physical XML cannot become missing recovery when SharedPreferences is empty`() = runBlocking {
        for (raw in listOf("", "<map><string name=\"active_restore\">PRIVATE_XML_SENTINEL", "<wrong-root/>")) {
            val root = temporary.newFolder()
            val file = File(root, "shared_prefs/$PREFS_NAME.xml").apply { parentFile!!.mkdirs(); writeText(raw) }
            val coldEmpty = emptyCacheJournal(root)
            val observed = failure { coldEmpty.read() }
            assertTrue(observed is RestoreJournalCorruptedException)
            assertFalse(observed.toString().contains("PRIVATE_XML_SENTINEL"))
            failure { coldEmpty.begin(entry()) }
            assertEquals("Corrupt file remains available for recovery diagnosis", raw, file.readText())
            assertFalse("No journal write after a false Missing", prefs.contains(KEY_ACTIVE))
        }
    }

    @Test
    fun `active physical XML hidden by an empty cache prevents a new operation`() = runBlocking {
        val root = temporary.newFolder()
        val raw = "<map><string name=\"active_restore\">PRIVATE_XML_SENTINEL</string></map>"
        val file = File(root, "shared_prefs/$PREFS_NAME.xml").apply { parentFile!!.mkdirs(); writeText(raw) }
        val coldEmpty = emptyCacheJournal(root)
        assertTrue(failure { coldEmpty.read() } is RestoreJournalCorruptedException)
        failure { coldEmpty.begin(entry()) }
        assertEquals(raw, file.readText())
        assertFalse(prefs.contains(KEY_ACTIVE))
    }

    @Test
    fun `unprocessed physical backup cannot appear as an empty journal`() = runBlocking {
        val root = temporary.newFolder()
        val main = File(root, "shared_prefs/$PREFS_NAME.xml").apply { parentFile!!.mkdirs(); writeText("<map/>") }
        val backup = File(root, "shared_prefs/$PREFS_NAME.xml.bak").apply { writeText("PRIVATE_BACKUP_SENTINEL") }
        assertTrue(failure { emptyCacheJournal(root).read() } is RestoreJournalCorruptedException)
        assertEquals("<map/>", main.readText())
        assertEquals("PRIVATE_BACKUP_SENTINEL", backup.readText())
    }

    @Test
    fun `valid empty physical XML and absent physical file both remain missing`() = runBlocking {
        val root = temporary.newFolder()
        val coldEmpty = emptyCacheJournal(root)
        assertNull(coldEmpty.read())
        val file = File(root, "shared_prefs/$PREFS_NAME.xml").apply { parentFile!!.mkdirs(); writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<map />") }
        assertNull(coldEmpty.read())
        assertTrue(file.isFile)
        assertFalse(prefs.contains(KEY_ACTIVE))
    }

    /** Android maps XML parser errors to an empty cache; the physical file is real and kept separate. */
    private fun emptyCacheJournal(dataDirectory: File): SharedPrefsRestoreJournal {
        val emptyPreferences = object : SharedPreferences by prefs {
            override fun getString(key: String?, defValue: String?): String? = null
        }
        val wrappedContext = object : ContextWrapper(context) {
            override fun getDataDir(): File = dataDirectory
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = emptyPreferences
        }
        return SharedPrefsRestoreJournal(wrappedContext, dispatchers)
    }

    private fun faultJournal(
        applyBeforeFailure: Boolean = false,
        shouldFail: () -> Boolean,
    ): SharedPrefsRestoreJournal {
        val wrappedPrefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }

                    override fun remove(key: String?): SharedPreferences.Editor {
                        delegate.remove(key)
                        return this
                    }

                    override fun commit(): Boolean {
                        if (!shouldFail()) return delegate.commit()
                        if (applyBeforeFailure) delegate.apply()
                        return false
                    }
                }
            }
        }
        val wrappedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = wrappedPrefs
        }
        return SharedPrefsRestoreJournal(wrappedContext, dispatchers)
    }

    private suspend fun failure(block: suspend () -> Unit): Exception {
        var caught: Exception? = null
        try {
            block()
        } catch (exception: Exception) {
            caught = exception
        }
        assertNotNull("Operation must fail closed", caught)
        return checkNotNull(caught)
    }

    private fun entry(id: String = "p1") = RestoreJournal.ActiveEntry(
        planId = id,
        phase = RestoreJournal.Phase.PREPARING,
        payloadJson = "{\"payload\":\"nonempty|中文\"}",
        planRecordJson = "{\"overwriteServerIds\":[\"srv\"]}",
        protectiveSnapshotJson = "{\"before\":\"prior value\"}",
        includePreferences = true,
        startedAtEpochMs = 123,
    )

    private companion object {
        const val PREFS_NAME = "mediahub_restore_journal"
        const val KEY_ACTIVE = "active_restore"
    }
}
