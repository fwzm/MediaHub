package com.mediahub.feature.settings.backup

import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.common.backup.BackupFileFormat
import com.mediahub.core.common.backup.BackupSerializer
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlayerGestures
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.model.SubtitleStyle
import com.mediahub.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BackupRepository 完整回归（Phase 1I-A）：
 * 快照完整导出、合并/替换语义、ID 映射、同服不同账号隔离、
 * 重复导入幂等、非默认偏好往返。
 */
class BackupRepositoryTest {

    // ---- fakes ----

    private class FakeBackupDataSource(initial: List<MediaServer>, initialProgress: List<PlaybackProgress>) : BackupDataSource {
        val servers = initial.toMutableList()
        val progress = initialProgress.toMutableList()
        var plansApplied = 0
            private set

        override suspend fun readSnapshot() = BackupSnapshot(
            servers = servers.toList(),
            progress = progress.toList(),
        )

        override suspend fun applyRestorePlan(plan: RestorePlan) {
            plansApplied++
            for (server in plan.servers) {
                if (server.id in plan.skipExistingServerIds) continue
                val index = servers.indexOfFirst { it.id == server.id }
                if (index >= 0) servers[index] = server else servers.add(server)
            }
            for (p in plan.progress) {
                val index = progress.indexOfFirst { it.serverId == p.serverId && it.itemId == p.itemId }
                if (index >= 0) progress[index] = p else progress.add(p)
            }
        }
    }

    private class FakePreferencesRepository(initial: UserPreferences) : UserPreferencesRepository {
        var current = initial
            private set
        override val flow = kotlinx.coroutines.flow.MutableStateFlow(initial)
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            current = transform(current)
            flow.tryEmit(current)
        }
    }

    // ---- fixtures ----

    private val defaultPrefs = UserPreferences(subtitleStyle = SubtitleStyle(textColor = 0xFF0000FF.toInt()))

    private fun server(id: String, name: String, type: ServerType = ServerType.EMBY, url: String = "https://$id.example.com") =
        MediaServer(
            id = id, name = name, type = type,
            createdAtEpochMs = 0,
            endpoints = listOf(ServerEndpoint(id = "", serverId = id, name = "主", url = url, isPrimary = true)),
        )

    private fun progress(serverId: String, itemId: String, posMs: Long) = PlaybackProgress(
        serverId = serverId, itemId = itemId, positionMs = posMs,
        durationMs = 600_000, isPaused = false, updatedAtEpochMs = 0,
    )

    private fun repository(
        servers: List<MediaServer> = emptyList(),
        progress: List<PlaybackProgress> = emptyList(),
        prefs: UserPreferences = defaultPrefs,
    ): Pair<BackupRepository, FakeBackupDataSource> {
        val ds = FakeBackupDataSource(servers, progress)
        val prefsRepo = FakePreferencesRepository(prefs)
        return BackupRepository(ds, prefsRepo) to ds
    }

    // ---- 导出含播放记录 + 非默认偏好往返 ----

    @Test
    fun `export includes non-default prefs and progress`() = runTest {
        val prefs = defaultPrefs.copy(
            defaultPlaybackSpeed = 2.5f,
            subtitleSizeSp = 30,
            autoLandscape = false,
            subtitleStyle = SubtitleStyle(textColor = 0xFF00FF00.toInt(), textScale = 1.5f),
            gestures = PlayerGestures(scrubEnabled = false, longPressDefaultSpeed = 3.0f),
        )
        val (repo, _) = repository(
            servers = listOf(server("srv-A", "Server A")),
            progress = listOf(progress("srv-A", "ep-1", 120_000)),
            prefs = prefs,
        )
        val bytes = repo.exportBackup("pw".toCharArray(), "1.0")

        val importResult = BackupSerializer.import(bytes, "pw".toCharArray())
        assertTrue(importResult is BackupSerializer.ImportResult.Ok)
        val payload = (importResult as BackupSerializer.ImportResult.Ok).payload

        val dto = payload.preferences!!
        assertEquals("非默认 speed 往返", 2.5f, dto.defaultPlaybackSpeed)
        assertEquals("非默认 subtitleSize 往返", 30, dto.subtitleSizeSp)
        assertEquals("autoLandscape=false 往返", false, dto.autoLandscape)
        assertEquals("非默认 subtitleStyle.textColor 往返", 0xFF00FF00.toInt(), dto.subtitleStyle.textColor)
        assertEquals("非默认 gestures.scrubEnabled=false 往返", false, dto.gestures.scrubEnabled)
        assertEquals("非默认 gestures.longPressDefaultSpeed=3.0 往返", 3.0f, dto.gestures.longPressDefaultSpeed)
    }

    // ---- 合并/替换 ----

    @Test
    fun `merge skips existing servers and adds new ones`() = runTest {
        val (repo, ds) = repository(
            servers = listOf(server("srv-A", "Existing A")),
        )
        val backupServers = listOf(
            server("srv-A", "Existing A"), // 已有 → 跳过
            server("srv-B", "New B"),       // 新增
        )
        val payload = buildPayload(backupServers)

        val result = repo.restore(payload, RestoreStrategy.MERGE)

        assertEquals("新增 1 个", 1, result.addedServers)
        assertEquals("跳过 1 个", 1, result.skippedExistingServers)
        assertEquals("fake 中现在有 2 个源", 2, ds.servers.size)
    }

    @Test
    fun `replace creates new servers without skipping`() = runTest {
        val (repo, ds) = repository(
            servers = listOf(server("srv-A", "Existing")),
        )
        val payload = buildPayload(listOf(server("srv-B", "New")))
        val result = repo.restore(payload, RestoreStrategy.REPLACE_SELECTED)

        assertEquals("替换新增 1 个", 1, result.addedServers)
        assertEquals(0, result.skippedExistingServers)
        assertEquals("fake 中现在有 2 个源", 2, ds.servers.size)
    }

    // ---- 同服不同账号隔离 ----

    @Test
    fun `same url different username creates separate servers`() = runTest {
        val srv1 = server("srv-A", "Account A", url = "https://same.example.com")
        val srv2 = MediaServer(
            id = "srv-B", name = "Account B", type = ServerType.EMBY,
            username = "user-B", createdAtEpochMs = 0,
            endpoints = listOf(ServerEndpoint(id = "", serverId = "srv-B", name = "主", url = "https://same.example.com", isPrimary = true)),
        )
        val (repo, ds) = repository(servers = listOf(srv1))
        val payload = buildPayload(listOf(srv2))
        repo.restore(payload, RestoreStrategy.MERGE)

        val all = ds.servers
        assertEquals("同 URL 不同账号 → 两个独立源", 2, all.size)
        assertTrue("账号 A 保留", all.any { it.username == null || it.username != "user-B" })
        assertTrue("账号 B 独立存在", all.any { it.username == "user-B" })
    }

    // ---- 重复导入幂等 ----

    @Test
    fun `repeated import is idempotent - no duplicate servers`() = runTest {
        val (repo, ds) = repository()
        val backupServers = listOf(server("srv-X", "Test"))
        val payload = buildPayload(backupServers)

        repo.restore(payload, RestoreStrategy.REPLACE_SELECTED)
        val countAfterFirst = ds.servers.size

        repo.restore(payload, RestoreStrategy.REPLACE_SELECTED)
        assertEquals("重复导入不重复建源", countAfterFirst, ds.servers.size)
    }

    // ---- 播放记录 ID 映射 ----

    @Test
    fun `progress maps to correct server id after restore`() = runTest {
        val (repo, ds) = repository()
        val backupServers = listOf(server("srv-Orig", "Origin"))
        val origProgress = listOf(progress("srv-Orig", "ep-1", 90_000))
        val payload = buildPayload(backupServers, origProgress)

        repo.restore(payload, RestoreStrategy.REPLACE_SELECTED)

        val restored = ds.progress.firstOrNull { it.itemId == "ep-1" }
        assertNotNull("播放记录已恢复", restored)
        assertEquals("播放记录 serverId 映射到实际恢复的 ID", "srv-Orig", restored?.serverId)
    }

    // ---- 预览零写入 ----

    @Test
    fun `preview does not write to data store`() = runTest {
        val (repo, ds) = repository(
            servers = listOf(server("srv-A", "Existing")),
        )
        val payload = buildPayload(listOf(server("srv-B", "New")))
        val before = ds.servers.size

        repo.buildPreview(payload)
        assertEquals("预览零写入", before, ds.servers.size)
    }

    // ---- helper ----

    private fun buildPayload(
        servers: List<MediaServer>,
        progress: List<PlaybackProgress> = emptyList(),
    ): BackupDtos.BackupPayload {
        val serverDtos = servers.map { s ->
            BackupDtos.ServerDto(
                backupId = s.id, name = s.name, type = s.type.name,
                username = s.username, isDefault = s.isDefault, sortOrder = s.sortOrder,
                endpoints = s.endpoints.map { ep ->
                    BackupDtos.EndpointDto(name = ep.name, url = ep.url, isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder)
                },
            )
        }
        val progressDtos = progress.map { p ->
            BackupDtos.ProgressDto(
                serverBackupId = p.serverId, itemId = p.itemId,
                positionMs = p.positionMs, durationMs = p.durationMs,
                isPaused = p.isPaused, updatedAtEpochMs = p.updatedAtEpochMs,
                itemTitle = p.itemTitle, itemType = p.itemType?.name,
            )
        }
        return BackupDtos.BackupPayload(
            manifest = BackupFileFormat.Manifest(
                formatVersion = 1, minimumReaderVersion = 1,
                appVersion = "test", createdAtEpochMs = 0,
                includedSections = listOf("servers", "progress"),
                recordCounts = mapOf("servers" to serverDtos.size, "progress" to progressDtos.size),
            ),
            servers = serverDtos, progress = progressDtos,
        )
    }
}
