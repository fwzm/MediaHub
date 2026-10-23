package com.mediahub.core.database.sync

import androidx.room.Room
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W6-C：可用路径接在**真实 Room 库**上的行为（Robolectric + 内存库，同 ServerSyncStoreTest 范式）。
 *
 * 纯 JVM 的 AndroidSourceSyncServiceTest 用 fake 钉住语义；这里只验证"契约 → 持久化"这一段：
 * 导入后三列真的落到 servers 表、二次导入不再产生行、导出会给老来源补号且补号稳定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSourceSyncServiceRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ServerRepository
    private lateinit var service: AndroidSourceSyncService

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ServerRepository(db)
        service = AndroidSourceSyncService(
            store = repository,
            deviceId = "device-android",
            snapshotIdFactory = { "snap-room" },
            clock = { 1_700_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun goldenJson(): String =
        javaClass.getResourceAsStream("/sync/windows-envelope-device-a.json")!!
            .use { it.readBytes().decodeToString() }

    private fun legacyServer(name: String = "旧名"): MediaServer = MediaServer(
        id = "",
        name = name,
        type = ServerType.EMBY,
        username = "alice",
        createdAtEpochMs = 1_600_000_000_000L,
        endpoints = listOf(
            ServerEndpoint(
                id = "",
                serverId = "",
                name = "主线路",
                url = "http://nas-a:8096/emby",
                isPrimary = true,
                enabled = true,
                sortOrder = 0,
            ),
        ),
    )

    @Test
    fun importing_the_windows_envelope_creates_rows_with_identity_columns() {
        val result = runBlocking { service.importEnvelope(goldenJson()) }

        assertEquals(2, result.appliedCount)
        assertEquals(2, runBlocking { db.serverDao().count() })

        val emby = runBlocking { db.serverDao().getBySyncSourceId("local-385c7c48494fcb81") }
        assertNotNull(emby)
        assertEquals("客厅 Emby", emby!!.name)
        assertEquals("EMBY", emby.type)
        assertEquals("emby", emby.syncSourceTypeRaw)
        assertEquals(3L, emby.syncRecordRevision)
        assertEquals("alice", emby.username)

        val endpoints = runBlocking { db.serverEndpointDao().getByServer(emby.id) }
        assertEquals(1, endpoints.size)
        assertEquals("http://nas-a:8096/emby", endpoints.single().url)
        assertTrue(endpoints.single().isPrimary)
    }

    @Test
    fun unknown_wire_type_is_not_persisted() {
        runBlocking { service.importEnvelope(goldenJson()) }

        val all = runBlocking { db.serverDao().getAll() }
        assertEquals(2, all.size)
        assertTrue(all.none { it.syncSourceTypeRaw == "quantum-nas" })
        assertNull(runBlocking { db.serverDao().getBySyncSourceId("local-76e5f67669136fae") })
    }

    @Test
    fun importing_the_same_envelope_twice_does_not_add_rows() {
        runBlocking { service.importEnvelope(goldenJson()) }
        val second = runBlocking { service.importEnvelope(goldenJson()) }

        assertEquals(0, second.appliedCount)
        assertEquals(3, second.ignoredCount)
        assertEquals(1, second.requiresApprovalCount)
        assertEquals(2, runBlocking { db.serverDao().count() })
    }

    @Test
    fun import_updates_an_existing_bound_source_in_place() {
        val created = runBlocking { repository.addServer(legacyServer(name = "旧名")) }
        runBlocking { repository.bindSyncSourceId(created.id, "local-385c7c48494fcb81", 1L, "emby") }

        runBlocking { service.importEnvelope(goldenJson()) }

        val entity = runBlocking { db.serverDao().getById(created.id) }
        assertNotNull(entity)
        assertEquals("客厅 Emby", entity!!.name)
        assertEquals(3L, entity.syncRecordRevision)
        // 另一条来源（jellyfin）被新建，旧行不被复制
        assertEquals(2, runBlocking { db.serverDao().count() })
    }

    @Test
    fun export_assigns_a_stable_sync_id_to_a_pre_w6_source() {
        val created = runBlocking { repository.addServer(legacyServer()) }
        assertNull(runBlocking { db.serverDao().getById(created.id) }!!.syncSourceId)

        val first = runBlocking { service.export() }
        val afterFirst = runBlocking { db.serverDao().getById(created.id) }!!.syncSourceId
        assertNotNull(afterFirst)
        assertTrue(afterFirst!!.startsWith("local-"))
        assertEquals(22, afterFirst.length)
        assertEquals(listOf(created.id), first.derivedIdentityServerIds)
        assertEquals(afterFirst, SourceSyncCodec.decode(first.json).sources.single().syncSourceId)

        val second = runBlocking { service.export() }
        assertTrue(second.derivedIdentityServerIds.isEmpty())
        assertEquals(afterFirst, runBlocking { db.serverDao().getById(created.id) }!!.syncSourceId)
        assertEquals(afterFirst, SourceSyncCodec.decode(second.json).sources.single().syncSourceId)
    }

    @Test
    fun exporting_after_import_produces_a_self_consistent_envelope() {
        runBlocking { service.importEnvelope(goldenJson()) }

        val export = runBlocking { service.export() }
        val decoded = SourceSyncCodec.decode(export.json)

        assertEquals("device-android", decoded.deviceId)
        assertEquals(2, decoded.sources.size)
        assertTrue(export.skippedServerIds.isEmpty())
        assertFalse(decoded.capabilities.canSendSecrets)
        assertEquals(
            listOf("http://nas-a:8096/emby", "http://nas-a:8096/jelly"),
            decoded.sources.map { it.endpoints.single().url }.sorted(),
        )
    }
}
