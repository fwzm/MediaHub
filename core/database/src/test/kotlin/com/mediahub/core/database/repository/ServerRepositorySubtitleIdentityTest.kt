package com.mediahub.core.database.repository

import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual Room reads/writes; this does not simulate remote server item ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerRepositorySubtitleIdentityTest {
    private lateinit var db: AppDatabase
    private lateinit var servers: ServerRepository
    private lateinit var memories: SubtitleMemoryRepository
    private lateinit var original: MediaServer
    private lateinit var key: String
    private lateinit var otherKey: String

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        servers = ServerRepository(db)
        memories = SubtitleMemoryRepository(db)
        original = MediaServer(
            id = "srv-live", name = "WebDAV", type = ServerType.WEBDAV,
            username = "Alice", createdAtEpochMs = 1,
            endpoints = listOf(
                ServerEndpoint("primary", "srv-live", "Primary", "https://dav.example/media", isPrimary = true),
                ServerEndpoint("backup", "srv-live", "Backup", "https://backup.example/media", sortOrder = 1),
            ),
        )
        servers.addServer(original)
        original = servers.getServer(original.id)!!
        key = SubtitleMemoryKeys.forItem(item(original.id))
        otherKey = SubtitleMemoryKeys.forItem(item("srv-other"))
        memories.remember(SubtitleMemoryEntry(key, original.id, null, 1500, 1))
        memories.remember(SubtitleMemoryEntry(otherKey, "srv-other", null, 250, 1))
    }

    @After
    fun tearDown() = db.close()

    private fun item(serverId: String) = MediaItem(
        serverId = serverId, id = "https://dav.example/media/movie.mkv",
        type = MediaType.MOVIE, title = "Movie", path = "https://dav.example/media/movie.mkv",
        sizeBytes = 700_000_000,
    )

    private fun withActiveUrl(url: String) = original.copy(
        endpoints = original.endpoints.map { if (it.id == "primary") it.copy(url = url) else it },
    )

    private suspend fun assertIdentityReplaced(candidate: MediaServer) {
        assertEquals(1500L, memories.recall(key)!!.offsetMs)
        servers.updateServer(candidate)
        assertEquals(candidate.username, servers.getServer(original.id)!!.username)
        assertNull("Old offset-only memory must not be available to the replacement identity", memories.recall(key))
        assertEquals(250L, memories.recall(otherKey)!!.offsetMs)
    }

    @Test
    fun `same server id url and size changed account cannot recall old offset`() = runBlocking {
        // Both accounts' hypothetical successful detail responses produce this same actual key.
        // We assert the local persistence boundary, not that a real remote server returned them.
        assertEquals(key, SubtitleMemoryKeys.forItem(item(original.id)))
        assertNull(memories.recall(key)!!.subtitleId)
        assertIdentityReplaced(original.copy(username = "Bob"))
    }

    @Test
    fun `username case is an identity change`() = runBlocking {
        assertIdentityReplaced(original.copy(username = "alice"))
    }

    @Test
    fun `active origin change clears old memory`() = runBlocking {
        assertIdentityReplaced(withActiveUrl("https://replacement.example/media"))
    }

    @Test
    fun `reverse proxy path change clears old memory`() = runBlocking {
        assertIdentityReplaced(withActiveUrl("https://dav.example/other-account"))
    }

    @Test
    fun `provider type change clears old memory`() = runBlocking {
        assertIdentityReplaced(original.copy(type = ServerType.EMBY))
    }

    @Test
    fun `metadata and nonactive endpoint changes retain memory`() = runBlocking {
        val edited = original.copy(name = "Renamed", note = "Note", icon = "builtin://webdav",
            endpoints = original.endpoints.map { if (it.id == "backup") it.copy(url = "https://new-backup.example") else it })
        servers.updateServer(edited)
        assertEquals(edited.name, servers.getServer(original.id)!!.name)
        assertEquals(1500L, memories.recall(key)!!.offsetMs)
    }

    @Test
    fun `normalized active address and trimmed username retain memory`() = runBlocking {
        servers.updateServer(withActiveUrl("HTTPS://DAV.EXAMPLE/media/").copy(username = " Alice "))
        assertEquals(1500L, memories.recall(key)!!.offsetMs)
    }

    @Test
    fun `switching active endpoint clears memory even when urls are unchanged`() = runBlocking {
        servers.updateServer(original.copy(endpoints = original.endpoints.map { it.copy(isPrimary = it.id == "backup") }))
        assertNull(memories.recall(key))
    }

    @Test
    fun `different stored content tree addresses remain distinct identities`() = runBlocking {
        // MediaServer has no separate treeUri field. Stored LOCAL endpoint URLs, if present,
        // use the same full-address identity rule as restore; this is not a SAF navigation test.
        val local = withActiveUrl("content://com.android.externalstorage.documents/tree/primary%3AMovies")
            .copy(type = ServerType.LOCAL)
        servers.updateServer(local)
        memories.remember(SubtitleMemoryEntry(key, local.id, null, 1500, 1))
        servers.updateServer(local.copy(endpoints = local.endpoints.map {
            if (it.id == "primary") it.copy(url = "content://com.android.externalstorage.documents/tree/primary%3AOther") else it
        }))
        assertNull(memories.recall(key))
    }

    @Test
    fun `local metadata edit with no stored endpoints retains memory`() = runBlocking {
        val local = original.copy(type = ServerType.LOCAL, username = null, endpoints = emptyList())
        servers.updateServer(local)
        memories.remember(SubtitleMemoryEntry(key, local.id, null, 1500, 1))
        servers.updateServer(local.copy(name = "Local files", note = "Metadata only"))
        assertEquals(1500L, memories.recall(key)!!.offsetMs)
    }

    @Test
    fun `failed endpoint write rolls back server endpoints and memory deletion`() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_replacement BEFORE INSERT ON server_endpoints
            WHEN NEW.url = 'https://blocked.example'
            BEGIN SELECT RAISE(ABORT, 'fixture rejects replacement'); END
        """.trimIndent())
        val result = runCatching { servers.updateServer(withActiveUrl("https://blocked.example").copy(username = "Bob")) }
        assertTrue("Failure fixture must execute", result.isFailure)
        assertEquals(original, servers.getServer(original.id))
        assertEquals(1500L, memories.recall(key)!!.offsetMs)
        assertEquals(250L, memories.recall(otherKey)!!.offsetMs)
    }
}
