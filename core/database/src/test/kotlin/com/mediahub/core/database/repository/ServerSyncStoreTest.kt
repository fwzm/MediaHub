package com.mediahub.core.database.repository

import androidx.room.Room
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.entity.ServerEntity
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
 * W6-B：servers 表上的同步身份读写（ServerRepository 的 SyncSourceStore 实现 + 三个写方法）。
 *
 * 用 Robolectric + Room 内存库（同 ServerRepositorySubtitleIdentityTest 范式）；验证
 * 身份往返、修订号写回、唯一约束、生效线路地址、未知 wire 类型保真、以及
 * "编辑服务器不得抹掉同步身份" 这条最容易回归的边界。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerSyncStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ServerRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ServerRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun endpoint(
        serverId: String,
        url: String,
        isPrimary: Boolean,
        sortOrder: Int = 0,
        enabled: Boolean = true,
    ) = ServerEndpoint(
        id = serverId + "_ep" + sortOrder,
        serverId = serverId,
        name = "线路" + sortOrder,
        url = url,
        isPrimary = isPrimary,
        enabled = enabled,
        sortOrder = sortOrder,
    )

    private fun server(
        id: String = "srv-1",
        name: String = "客厅 Emby",
        type: ServerType = ServerType.EMBY,
        username: String? = "alice",
        endpoints: List<ServerEndpoint> = listOf(
            endpoint(id, "http://nas:8096/emby", isPrimary = true),
        ),
    ) = MediaServer(
        id = id,
        name = name,
        type = type,
        username = username,
        createdAtEpochMs = 0,
        endpoints = endpoints,
    )

    @Test
    fun `bind then find returns the persisted sync identity`() = runBlocking {
        repository.addServer(server())

        assertTrue(repository.bindSyncSourceId("srv-1", "local-abc", 3L, "emby"))

        val record = repository.findBySyncSourceId("local-abc")
        assertNotNull(record)
        assertEquals("srv-1", record!!.serverId)
        assertEquals("local-abc", record.syncSourceId)
        assertEquals(3L, record.recordRevision)
        assertEquals("emby", record.sourceTypeRaw)
        assertEquals(ServerType.EMBY, record.type)
        assertEquals("http://nas:8096/emby", record.url)
        assertEquals("客厅 Emby", record.displayName)
        assertEquals("alice", record.accountScope)
    }

    @Test
    fun `find by unknown identity returns null`() = runBlocking {
        repository.addServer(server())
        assertNull(repository.findBySyncSourceId("local-missing"))
    }

    @Test
    fun `bind reports missing server as false`() = runBlocking {
        assertFalse(repository.bindSyncSourceId("nope", "local-abc"))
        assertNull(repository.findBySyncSourceId("local-abc"))
    }

    @Test
    fun `update revision persists and reports missing server`() = runBlocking {
        repository.addServer(server())
        repository.bindSyncSourceId("srv-1", "local-abc", 1L)

        assertTrue(repository.updateSyncRecordRevision("srv-1", 7L))
        assertEquals(7L, repository.findBySyncSourceId("local-abc")!!.recordRevision)
        // 身份其余列保持不变
        assertEquals("srv-1", repository.findBySyncSourceId("local-abc")!!.serverId)
        assertFalse(repository.updateSyncRecordRevision("nope", 9L))
    }

    @Test
    fun `duplicate sync identity is rejected by the unique index`() = runBlocking {
        repository.addServer(server(id = "srv-1", name = "A"))
        repository.addServer(
            server(
                id = "srv-2",
                name = "B",
                endpoints = listOf(endpoint("srv-2", "http://nas:8096/jelly", isPrimary = true)),
            ),
        )
        assertTrue(repository.bindSyncSourceId("srv-1", "local-dup"))

        val failure = runCatching { repository.bindSyncSourceId("srv-2", "local-dup") }

        assertTrue("同一同步身份绑第二条来源必须失败（唯一索引）", failure.isFailure)
        // 第二条来源的身份保持 null，且原绑定未被改动
        assertNull(db.serverDao().getById("srv-2")!!.syncSourceId)
        assertEquals("srv-1", repository.findBySyncSourceId("local-dup")!!.serverId)
    }

    @Test
    fun `list local sources uses the active primary endpoint url`() = runBlocking {
        repository.addServer(
            server(
                endpoints = listOf(
                    // addServer 强制 index 0 为主线路；这里把它禁用，生效线路应回落到下一条 enabled 线路
                    endpoint("srv-1", "http://primary-but-disabled:8096/emby", isPrimary = true, sortOrder = 0, enabled = false),
                    endpoint("srv-1", "http://fallback:8096/emby", isPrimary = false, sortOrder = 1),
                ),
            ),
        )

        val record = repository.listLocalSources().single()

        assertEquals("http://fallback:8096/emby", record.url)
    }

    @Test
    fun `list local sources keeps unknown raw wire type and nulls the type`() = runBlocking {
        db.serverDao().upsert(
            ServerEntity(
                id = "srv-q",
                name = "Quantum NAS",
                type = "LOCAL",
                createdAtEpochMs = 0,
                syncSourceId = "local-q",
                syncRecordRevision = 2L,
                syncSourceTypeRaw = "quantum-nas",
            ),
        )

        val record = repository.listLocalSources().single()

        assertNull(record.type)
        assertEquals("quantum-nas", record.sourceTypeRaw)
        assertEquals("local-q", record.syncSourceId)
        assertEquals(2L, record.recordRevision)
        assertNull(record.url)
    }

    @Test
    fun `update server preserves the sync identity`() = runBlocking {
        repository.addServer(server())
        repository.bindSyncSourceId("srv-1", "local-abc", 5L, "emby")

        val current = repository.getServer("srv-1")!!
        repository.updateServer(current.copy(name = "改名后的 Emby"))

        val record = repository.findBySyncSourceId("local-abc")
        assertNotNull("改名不得抹掉同步身份", record)
        assertEquals("改名后的 Emby", record!!.displayName)
        assertEquals(5L, record.recordRevision)
        assertEquals("emby", record.sourceTypeRaw)
        assertEquals("改名后的 Emby", repository.getServer("srv-1")!!.name)
    }

    @Test
    fun `apply sync identity writes the three columns only`() = runBlocking {
        repository.addServer(server())

        val applied = repository.applySyncIdentity(
            "srv-1",
            LocalSourceRecord(
                serverId = "srv-1",
                syncSourceId = "local-j",
                type = ServerType.JELLYFIN,
                recordRevision = 4L,
            ),
        )

        assertTrue(applied)
        val entity = db.serverDao().getById("srv-1")!!
        assertEquals("local-j", entity.syncSourceId)
        assertEquals(4L, entity.syncRecordRevision)
        // 原始 wire 由 type 反推（调用方没给 raw 时的兜底）
        assertEquals("jellyfin", entity.syncSourceTypeRaw)
        // 用户可见字段不变
        assertEquals("EMBY", entity.type)
        assertEquals("客厅 Emby", entity.name)
        assertEquals("alice", entity.username)
    }

    @Test
    fun `multiple unsynchronized servers coexist with null identity`() = runBlocking {
        repository.addServer(server(id = "srv-1", name = "A"))
        repository.addServer(
            server(id = "srv-2", name = "B", endpoints = listOf(endpoint("srv-2", "http://b:8096", isPrimary = true))),
        )

        val records = repository.listLocalSources()

        assertEquals(2, records.size)
        assertTrue("未同步来源的身份必须是 null: " + records.map { it.syncSourceId }, records.all { it.syncSourceId == null })
        assertTrue(records.all { it.recordRevision == 0L })
    }
}
