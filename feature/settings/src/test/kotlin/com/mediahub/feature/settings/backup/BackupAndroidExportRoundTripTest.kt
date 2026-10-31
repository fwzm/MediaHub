package com.mediahub.feature.settings.backup

import androidx.room.Room
import com.mediahub.core.common.AppDispatchers
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.common.backup.BackupSerializer
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.model.SubtitleStyle
import com.mediahub.model.UserPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 * W28：Android 生产导出 -> Android 生产恢复 -> 真 Room 落库的往返证据。
 *
 * 与 W25 的 Windows 实文件互补：那一轮证明「电脑导出、手机读得懂」，
 * 这一轮证明「手机自己导出的字节，能在另一台手机（清空后的库）真正落库」——
 * 不是 fake data source，而是 [ProductionBackupDataSource] 的事务写入。
 *
 * 被测字节由 [theExportCanBeRegeneratedFromARealRoomLibrary] 用生产导出路径生成；
 * 同一份字节需要作为样本落到 core/common 测试资源与 Windows tests/assets，
 * 供两端各读一次（见 docs §19）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupAndroidExportRoundTripTest {

    private val password = "跨平台备份-测试口令-2026".toCharArray()

    private lateinit var db: AppDatabase
    private lateinit var dataSource: ProductionBackupDataSource
    private lateinit var preferences: InMemoryPreferencesRepository
    private lateinit var journal: InMemoryRestoreJournal
    private lateinit var repository: BackupRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataSource = ProductionBackupDataSource(db)
        preferences = InMemoryPreferencesRepository(defaultPreferences())
        journal = InMemoryRestoreJournal()
        repository = BackupRepository(
            dataSource, preferences, journal,
            object : RestoreLoginInvalidator {
                override suspend fun invalidate(serverId: String) = Unit
            },
            AppDispatchers(io = Dispatchers.IO),
            MemoryRestoreSnapshotStorage(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- 生产路径：真 Room 库里导出，再清空、再恢复 ----

    @Test
    fun theAndroidExportLandsBackInARealRoomLibrary() = runBlocking {
        seedLibrary()
        val bytes = repository.exportBackup(password, "android-0.1.0")
        assertTrue("导出必须是一份真容器，实际 ${bytes.size} 字节", bytes.size > 1_000)

        // 另一台手机：库是空的、偏好是默认值。
        db.clearAllTables()
        preferences.reset(defaultPreferences())

        val prepared = repository.prepareRestore(bytes, password)
        assertTrue("期望 Prepared，实际 $prepared", prepared is PrepareResult.Prepared)
        val validated = (prepared as PrepareResult.Prepared).validated
        assertEquals("导出必须含播放记录段", 2, validated.progress.size)
        assertEquals("导出必须含偏好段", true, validated.preferences != null)
        assertEquals(0, validated.orphanProgressRecords)

        val preview = repository.buildPreview(validated, RestoreStrategy.REPLACE_SELECTED)
        assertEquals(4, preview.newServers)
        assertEquals(0, preview.identicalServers)
        assertEquals(0, preview.conflictingServers)
        assertEquals("播放记录应逐条可写", 2, preview.progressToWrite)
        assertTrue(preview.preferencesContained)
        assertTrue(preview.preferencesWillRestore)

        val result = repository.restoreWithFreshPreview(validated, RestoreStrategy.REPLACE_SELECTED, replaceConfirmed = true)
        assertEquals(4, result.addedServers)
        assertEquals(0, result.overwrittenServers)
        assertEquals(2, result.restoredProgress)
        assertTrue(result.preferencesRestored)

        // 真 Room 里的行：类型、身份、端点、默认源、播放记录一条不差。
        val after = dataSource.readSnapshot()
        assertEquals(4, after.servers.size)
        val byType = after.servers.associateBy { it.type }
        val emby = byType.getValue(ServerType.EMBY)
        assertEquals("Home Emby", emby.name)
        assertEquals("user-A", emby.username)
        assertEquals("客厅", emby.note)
        assertTrue(emby.isDefault)
        assertEquals(0, emby.sortOrder)
        assertEquals(1, emby.endpoints.size)
        assertEquals("https://media.example:8920/emby/", emby.endpoints.single().url)

        val jellyfin = byType.getValue(ServerType.JELLYFIN)
        assertNull("空账号必须保持 null，不能变成空串", jellyfin.username)
        assertEquals(1, jellyfin.sortOrder)
        assertFalse(jellyfin.isDefault)

        assertEquals("Team DAV", byType.getValue(ServerType.WEBDAV).name)

        val local = byType.getValue(ServerType.LOCAL)
        assertEquals("我的本地文件夹", local.name)
        assertTrue("LOCAL 不携带设备绑定端点", local.endpoints.isEmpty())

        assertEquals(2, after.progress.size)
        val first = after.progress.single { it.itemId == "item-001" }
        assertEquals(540_000L, first.positionMs)
        assertEquals(1_440_000L, first.durationMs)
        assertTrue(first.isPaused)
        assertEquals("第一集", first.itemTitle)
        assertEquals(MediaType.EPISODE, first.itemType)
        assertEquals("item-002", after.progress.single { it.serverId == emby.id && it.itemId == "item-002" }.itemId)

        // 偏好段是 v1 白名单：白名单内的每一项都要换成本机的默认值 -> 备份里的值。
        val restored = preferences.flow.value
        assertEquals(PlaybackEngineMode.MEDIA3, restored.playbackEngineMode)
        assertEquals(1.5f, restored.defaultPlaybackSpeed, 0.0001f)
        assertEquals(22, restored.subtitleSizeSp)
        assertFalse(restored.autoPlayNextEpisode)
        assertTrue(restored.showPlayerInfoOverlay)
        assertEquals(0xFF00FF00.toInt(), restored.subtitleStyle.textColor)
        // 白名单外的两段（v1 导出没有这两个键）必须保留本机选择，不能被清成默认。
        assertEquals(
            "白名单外的偏好段应保留本机值",
            defaultPreferences().playerVisualEffects,
            restored.playerVisualEffects,
        )
        assertEquals(defaultPreferences().professionalInfo, restored.professionalInfo)
    }

    /**
     * 生成/刷新跨端样本：把生产导出写进 build/ 目录，再读回来确认是合法容器。
     * 样本（`android-export.mhbackup`）由本用例的实际输出复制，不手改字节。
     */
    @Test
    fun theExportCanBeRegeneratedFromARealRoomLibrary() = runBlocking {
        seedLibrary()
        val bytes = repository.exportBackup(password, "android-0.1.0")
        val output = File("build/cross-platform").apply { mkdirs() }.resolve("android-export.mhbackup")
        output.writeBytes(bytes)
        println("W28 sample: ${output.absolutePath} (${bytes.size} B)")

        val reopened = BackupSerializer.import(output.readBytes(), password)
        assertTrue("样本必须能被生产解析器打开，实际 $reopened", reopened is BackupSerializer.ImportResult.Ok)
        val payload = (reopened as BackupSerializer.ImportResult.Ok).payload
        assertEquals(4, payload.servers.size)
        assertEquals(2, payload.progress.size)
        assertNotNull(payload.preferences)
        assertEquals(listOf("servers", "progress", "preferences"), payload.manifest.includedSections)
    }

    // ---- 夹具 ----

    private suspend fun seedLibrary() {
        val servers = listOf(
            MediaServer(
                id = "srv-emby", name = "Home Emby", type = ServerType.EMBY, username = "user-A", note = "客厅",
                isDefault = true, sortOrder = 0, createdAtEpochMs = 0,
                endpoints = listOf(endpoint("srv-emby", "主线路", "https://media.example:8920/emby/")),
            ),
            MediaServer(
                id = "srv-jellyfin", name = "Jelly", type = ServerType.JELLYFIN, username = null,
                isDefault = false, sortOrder = 1, createdAtEpochMs = 0,
                endpoints = listOf(endpoint("srv-jellyfin", "主线路", "https://jf.example:8096/jellyfin/")),
            ),
            MediaServer(
                id = "srv-webdav", name = "Team DAV", type = ServerType.WEBDAV, username = null,
                isDefault = false, sortOrder = 2, createdAtEpochMs = 0,
                endpoints = listOf(endpoint("srv-webdav", "主线路", "https://dav.example/dav/films")),
            ),
            MediaServer(
                id = "srv-local", name = "我的本地文件夹", type = ServerType.LOCAL, username = null,
                isDefault = false, sortOrder = 3, createdAtEpochMs = 0,
                endpoints = emptyList(),
            ),
        )
        val progress = listOf(
            PlaybackProgress(
                serverId = "srv-emby", itemId = "item-001",
                positionMs = 540_000, durationMs = 1_440_000, isPaused = true,
                updatedAtEpochMs = 1_790_000_000_000, itemTitle = "第一集", itemType = MediaType.EPISODE,
            ),
            PlaybackProgress(
                serverId = "srv-emby", itemId = "item-002",
                positionMs = 1_200_000, durationMs = 2_700_000, isPaused = false,
                updatedAtEpochMs = 1_790_000_100_000, itemTitle = "第二集", itemType = MediaType.EPISODE,
            ),
        )
        // 导出的偏好就是用户改过的那一份（播放内核、倍速、字号、连播、信息面板、字幕颜色）。
        preferences.reset(exportedPreferences())
        val record = BackupDtos.RestorePlanRecord(
            strategy = "REPLACE_SELECTED",
            overwriteServerIds = servers.map { it.id },
        )
        dataSource.applyRestorePlan(RestorePlan("seed-w28", record, servers, progress, null))
    }

    private fun endpoint(serverId: String, name: String, url: String) = ServerEndpoint(
        id = UUID.randomUUID().toString(), serverId = serverId, name = name, url = url,
        isPrimary = true, enabled = true, sortOrder = 0,
    )

    /** 另一台手机刚装好应用时的默认值（与导出侧不同，才能看出恢复真的写了东西）。 */
    private fun defaultPreferences() = UserPreferences()

    /** 导出侧用户改过的播放偏好。 */
    private fun exportedPreferences() = UserPreferences(
        playbackEngineMode = PlaybackEngineMode.MEDIA3,
        defaultPlaybackSpeed = 1.5f,
        subtitleSizeSp = 22,
        autoPlayNextEpisode = false,
        showPlayerInfoOverlay = true,
        subtitleStyle = SubtitleStyle(textColor = 0xFF00FF00.toInt()),
    )

    private class InMemoryPreferencesRepository(initial: UserPreferences) : UserPreferencesRepository {
        private val state = MutableStateFlow(initial)
        override val flow = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
        fun reset(prefs: UserPreferences) { state.value = prefs }
    }

    private class InMemoryRestoreJournal : RestoreJournal {
        private var entry: RestoreJournal.ActiveEntry? = null
        override suspend fun begin(entry: RestoreJournal.ActiveEntry) { this.entry = entry }
        override suspend fun markPhase(planId: String, phase: RestoreJournal.Phase) {
            entry = entry?.takeIf { it.planId == planId }?.copy(phase = phase)
        }
        override suspend fun markFailed(planId: String, cause: String) {
            entry = entry?.takeIf { it.planId == planId }?.copy(lastError = cause)
        }
        override suspend fun read(): RestoreJournal.ActiveEntry? = entry
        override suspend fun clear(planId: String) { if (entry?.planId == planId) entry = null }
    }
}
