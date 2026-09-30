package com.mediahub.feature.settings.backup

import androidx.room.Room
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.entity.PlaybackProgressEntity
import com.mediahub.core.database.entity.ServerEntity
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ProductionBackupDataSource 事务语义（Phase 1I review）：
 * 快照读取与计划写入都必须在单个 Room 事务内完成——
 * 服务器、线路、进度要么全部生效，要么全部不生效；接口注释与实现一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionBackupDataSourceTest {

    private lateinit var db: AppDatabase
    private lateinit var dataSource: ProductionBackupDataSource

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataSource = ProductionBackupDataSource(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun server(id: String, name: String) = MediaServer(
        id = id, name = name, type = ServerType.EMBY,
        createdAtEpochMs = 0,
        endpoints = listOf(
            ServerEndpoint(id = "", serverId = id, name = "主", url = "https://$id.example", isPrimary = true),
            ServerEndpoint(id = "", serverId = id, name = "备", url = "https://$id-alt.example", isPrimary = false),
        ),
    )

    @Test
    fun `applyRestorePlan writes servers endpoints and progress atomically`() = runBlocking {
        db.serverDao().upsert(
            ServerEntity(id = "srv-old", name = "Old", type = "EMBY", isDefault = true, sortOrder = 0, createdAtEpochMs = 0),
        )

        val plan = RestorePlan(
            planId = "p1",
            record = BackupDtos.RestorePlanRecord(
                strategy = "REPLACE_SELECTED",
                overwriteServerIds = listOf("srv-a", "srv-b"),
                idRemapping = mapOf("srv-a" to "srv-a", "srv-b" to "srv-b"),
                skipExistingServerIds = listOf("srv-old"),
            ),
            servers = listOf(server("srv-a", "A"), server("srv-b", "B")),
            progress = listOf(
                PlaybackProgress(
                    serverId = "srv-a", itemId = "ep-1", positionMs = 1_000,
                    durationMs = 60_000, isPaused = false, updatedAtEpochMs = 5,
                    itemTitle = "Episode 1",
                ),
            ),
            preferences = null,
        )

        dataSource.applyRestorePlan(plan)

        val snapshot = dataSource.readSnapshot()
        assertEquals("跳过行保留 + 新增 2 行", 3, snapshot.servers.size)
        val srvA = snapshot.servers.first { it.id == "srv-a" }
        assertEquals("线路随服务器一并写入", 2, srvA.endpoints.size)
        assertTrue(srvA.endpoints.any { it.url == "https://srv-a.example" })
        assertEquals("进度已写入", 1, snapshot.progress.size)
        assertEquals(1_000, snapshot.progress[0].positionMs)
    }

    @Test
    fun `skipExistingServerIds keep local rows untouched`() = runBlocking {
        db.serverDao().upsert(
            ServerEntity(id = "srv-a", name = "Local A", type = "EMBY", sortOrder = 0, createdAtEpochMs = 0),
        )
        val plan = RestorePlan(
            planId = "p2",
            record = BackupDtos.RestorePlanRecord(
                strategy = "MERGE",
                skipExistingServerIds = listOf("srv-a"),
                overwriteServerIds = emptyList(),
                idRemapping = mapOf("srv-a" to "srv-a"),
            ),
            servers = listOf(server("srv-a", "Backup A")),
            progress = emptyList(),
            preferences = null,
        )

        dataSource.applyRestorePlan(plan)

        val snapshot = dataSource.readSnapshot()
        assertEquals("同源行不被覆盖", "Local A", snapshot.servers.single().name)
    }

    @Test
    fun `overwrite replaces stale endpoints instead of merging`() = runBlocking {
        db.serverDao().upsert(
            ServerEntity(id = "srv-a", name = "Old A", type = "EMBY", sortOrder = 0, createdAtEpochMs = 0),
        )
        db.serverEndpointDao().upsert(
            com.mediahub.core.database.entity.ServerEndpointEntity(
                id = "srv-a_ep0", serverId = "srv-a", name = "旧线路", url = "https://stale.example", isPrimary = true,
            ),
        )
        val plan = RestorePlan(
            planId = "p3",
            record = BackupDtos.RestorePlanRecord(
                strategy = "REPLACE_SELECTED",
                overwriteServerIds = listOf("srv-a"),
                idRemapping = mapOf("srv-a" to "srv-a"),
            ),
            servers = listOf(server("srv-a", "New A")),
            progress = emptyList(),
            preferences = null,
        )

        dataSource.applyRestorePlan(plan)

        val endpoints = db.serverEndpointDao().getByServer("srv-a")
        assertEquals("旧线路被清除，仅剩新线路", 2, endpoints.size)
        assertTrue(endpoints.none { it.url == "https://stale.example" })
    }

    @Test
    fun `readSnapshot roundtrips progress display fields`() = runBlocking {
        db.playbackProgressDao().upsert(
            PlaybackProgressEntity(
                serverId = "srv-a", itemId = "ep-1", positionMs = 500,
                durationMs = 30_000, isPaused = true, updatedAtEpochMs = 9,
                itemTitle = "标题", itemType = "EPISODE",
            ),
        )

        val snapshot = dataSource.readSnapshot()

        val progress = snapshot.progress.single()
        assertEquals("标题", progress.itemTitle)
        assertEquals(com.mediahub.model.MediaType.EPISODE, progress.itemType)
        assertEquals(500, progress.positionMs)
    }

    @Test
    fun `SQLite abort after first server write rolls back servers endpoints and progress`() = runBlocking {
        val initial = RestorePlan("seed", BackupDtos.RestorePlanRecord("MERGE", overwriteServerIds = listOf("srv-a")),
            listOf(server("srv-a", "Original")), listOf(PlaybackProgress("srv-a", "old", 1, 100, true, 2)), null)
        dataSource.applyRestorePlan(initial)
        val before = dataSource.readSnapshot()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_restore_server BEFORE INSERT ON servers WHEN NEW.id = 'srv-b' BEGIN SELECT RAISE(ABORT, 'injected restore failure'); END",
        )
        try {
            val plan = RestorePlan("failed", BackupDtos.RestorePlanRecord("REPLACE_SELECTED", overwriteServerIds = listOf("srv-a", "srv-b")),
                listOf(server("srv-a", "Replacement"), server("srv-b", "New")),
                listOf(PlaybackProgress("srv-a", "incoming", 4, 100, true, 5)), null, before)
            assertTrue(runCatching { dataSource.applyRestorePlan(plan) }.isFailure)
            assertTrue("the real Room transaction restores the complete starting state", before.sameData(dataSource.readSnapshot()))
            assertTrue(dataSource.readSnapshot().servers.none { it.id == "srv-b" })
            assertTrue(dataSource.readSnapshot().progress.none { it.itemId == "incoming" })
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_restore_server")
        }
    }
}
