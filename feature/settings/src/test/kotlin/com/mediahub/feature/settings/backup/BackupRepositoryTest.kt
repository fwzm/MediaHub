package com.mediahub.feature.settings.backup

import com.mediahub.core.common.AppDispatchers
import com.mediahub.core.common.backup.BackupDtos
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BackupRepository 完整回归（Phase 1I review）：
 * 深度验证零写入、身份裁决与登录态隔离、MERGE newer-wins/冲突不导入/偏好不覆盖、
 * 替换确认防线、保护快照回滚、中断前向恢复、播放记录端到端。
 */
class BackupRepositoryTest {

    // ---- fakes ----

    private class FakeBackupDataSource(
        initialServers: List<MediaServer> = emptyList(),
        initialProgress: List<PlaybackProgress> = emptyList(),
    ) : BackupDataSource {
        val servers = initialServers.toMutableList()
        val progress = initialProgress.toMutableList()
        var applyCalls = 0
            private set
        /** 第 N 次 applyRestorePlan 在写完第 1 个服务器后抛错（模拟事务中途失败）。 */
        var failAfterFirstServerOnCall: Int? = null

        override suspend fun readSnapshot() = BackupSnapshot(
            servers = servers.toList(),
            progress = progress.toList(),
        )

        override suspend fun applyRestorePlan(plan: RestorePlan) {
            applyCalls++
            if (applyCalls == failAfterFirstServerOnCall) error("注入的数据库事务失败")
            val after = materializeRestorePlan(readSnapshot(), plan)
            servers.clear(); servers.addAll(after.servers)
            progress.clear(); progress.addAll(after.progress)
        }
    }

    private class FakePreferencesRepository(initial: UserPreferences) : UserPreferencesRepository {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
        override val flow = state
        val current: UserPreferences get() = state.value
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    private class FakeRestoreJournal : RestoreJournal {
        var entry: RestoreJournal.ActiveEntry? = null
        override suspend fun begin(entry: RestoreJournal.ActiveEntry) { this.entry = entry }
        override suspend fun markPhase(planId: String, phase: RestoreJournal.Phase) {
            entry = entry?.takeIf { it.planId == planId }?.copy(phase = phase, lastError = null)
        }
        override suspend fun markFailed(planId: String, cause: String) {
            entry = entry?.takeIf { it.planId == planId }?.copy(lastError = cause)
        }
        override suspend fun read(): RestoreJournal.ActiveEntry? = entry
        override suspend fun clear(planId: String) {
            if (entry?.planId == planId) entry = null
        }
    }

    private class FakeLoginInvalidator : RestoreLoginInvalidator {
        val invalidated = mutableListOf<String>()
        override suspend fun invalidate(serverId: String) { invalidated += serverId }
    }

    // ---- fixtures ----

    private val dispatcher = StandardTestDispatcher()
    private val defaultPrefs = UserPreferences(subtitleStyle = SubtitleStyle(textColor = 0xFF0000FF.toInt()))

    private fun server(
        id: String,
        name: String = "Server $id",
        type: ServerType = ServerType.EMBY,
        url: String = "https://$id.example.com",
        username: String? = null,
    ) = MediaServer(
        id = id, name = name, type = type, username = username,
        createdAtEpochMs = 0,
        endpoints = listOf(ServerEndpoint(id = "", serverId = id, name = "主", url = url, isPrimary = true)),
    )

    private fun progress(serverId: String, itemId: String, posMs: Long, updatedAt: Long = 0) = PlaybackProgress(
        serverId = serverId, itemId = itemId, positionMs = posMs,
        durationMs = 600_000, isPaused = false, updatedAtEpochMs = updatedAt,
    )

    private class Env(
        val repo: BackupRepository,
        val ds: FakeBackupDataSource,
        val prefs: FakePreferencesRepository,
        val journal: FakeRestoreJournal,
        val invalidator: FakeLoginInvalidator,
    )

    private fun env(
        servers: List<MediaServer> = emptyList(),
        progress: List<PlaybackProgress> = emptyList(),
        prefs: UserPreferences = defaultPrefs,
    ): Env {
        val ds = FakeBackupDataSource(servers, progress)
        val prefsRepo = FakePreferencesRepository(prefs)
        val journal = FakeRestoreJournal()
        val invalidator = FakeLoginInvalidator()
        val repo = BackupRepository(ds, prefsRepo, journal, invalidator, AppDispatchers(io = dispatcher), MemoryRestoreSnapshotStorage())
        return Env(repo, ds, prefsRepo, journal, invalidator)
    }

    /** 低成本加密：直接构造载荷字节（10k 迭代），供 prepareRestore 消费。 */
    private fun bytesOf(payload: BackupDtos.BackupPayload, pw: CharArray = "pw-pw".toCharArray()): ByteArray =
        authenticatedTestBytes(payload, pw)

    private fun payloadOf(
        servers: List<MediaServer>,
        progress: List<PlaybackProgress> = emptyList(),
        preferences: BackupDtos.PreferencesDto? = null,
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
            manifest = BackupDtosBackupManifest(serverDtos.size, progressDtos.size, preferences != null),
            servers = serverDtos, progress = progressDtos, preferences = preferences,
        )
    }

    private fun BackupDtosBackupManifest(servers: Int, progress: Int, prefs: Boolean) =
        com.mediahub.core.common.backup.BackupFileFormat.Manifest(
            formatVersion = 1, minimumReaderVersion = 1,
            appVersion = "test", createdAtEpochMs = 0,
            includedSections = listOf("servers", "progress", "preferences"),
            recordCounts = mapOf("servers" to servers, "progress" to progress, "preferences" to if (prefs) 1 else 0),
        )

    // ---- 导出：URL 值级守卫 ----

    @Test
    fun `export rejects url with user-info credentials`() = runTest(dispatcher) {
        val e = env(servers = listOf(server("srv-A", url = "https://user:fake-pass@srv-a.example.com")))
        try {
            e.repo.exportBackup("pw".toCharArray(), "1.0")
            throw AssertionError("导出应拒绝 user-info 凭据 URL")
        } catch (expected: ExportRejectedException) {
            assertTrue("错误应指向 user-info", expected.message!!.contains("user-info"))
        }
    }

    @Test
    fun `export rejects url with token query`() = runTest(dispatcher) {
        val e = env(servers = listOf(server("srv-A", url = "https://srv-a.example.com?api_key=FAKE123")))
        try {
            e.repo.exportBackup("pw".toCharArray(), "1.0")
            throw AssertionError("导出应拒绝敏感 query")
        } catch (expected: ExportRejectedException) {
            assertTrue("错误应指向 api_key", expected.message!!.contains("query"))
        }
    }

    // ---- 深度验证：枚举/引用零写入 ----

    @Test
    fun `unknown server type rejected with zero writes`() = runTest(dispatcher) {
        val e = env(servers = listOf(server("local", name = "Local")))
        val dirty = payloadOf(listOf(server("srv-X"))).copy(
            servers = payloadOf(listOf(server("srv-X"))).servers.map { it.copy(type = "NOT_A_TYPE") },
        )
        val result = e.repo.prepareRestore(bytesOf(dirty), "pw-pw".toCharArray())
        assertTrue(result is PrepareResult.Rejected)
        assertTrue((result as PrepareResult.Rejected).reason.contains("类型"))
        assertEquals("零写入：本机数据未动", 1, e.ds.servers.size)
        assertEquals(0, e.ds.applyCalls)
    }

    @Test
    fun `unknown playback engine mode rejected before any write`() = runTest(dispatcher) {
        val e = env()
        val dirty = payloadOf(listOf(server("srv-X")), preferences = BackupDtos.PreferencesDto(playbackEngineMode = "MAGIC"))
        val result = e.repo.prepareRestore(bytesOf(dirty), "pw-pw".toCharArray())
        assertTrue(result is PrepareResult.Rejected)
        assertEquals(0, e.ds.applyCalls)
    }

    @Test
    fun `orphan progress surfaced in validated restore not silently dropped`() = runTest(dispatcher) {
        val e = env()
        val payload = payloadOf(
            servers = listOf(server("srv-A")),
            progress = listOf(progress("srv-A", "ep-1", 1_000), progress("srv-Ghost", "ep-2", 2_000)),
        )
        val prepared = e.repo.prepareRestore(bytesOf(payload), "pw-pw".toCharArray())
        assertTrue(prepared is PrepareResult.Prepared)
        assertEquals("孤立引用计数披露", 1, (prepared as PrepareResult.Prepared).validated.orphanProgressRecords)
    }

    // ---- 预览零写入 + 冲突披露 ----

    @Test
    fun `preview exposes conflicts orphans and writes nothing`() = runTest(dispatcher) {
        val e = env(
            servers = listOf(
                server("srv-Keep", name = "本机保留"),
                server("srv-Conflict", name = "同ID不同址", url = "https://other.example.com"),
            ),
        )
        val payload = payloadOf(
            servers = listOf(
                server("srv-Keep"),
                server("srv-Conflict", name = "备份侧同ID", url = "https://elsewhere.example.com"),
                server("srv-New"),
            ),
            progress = listOf(
                progress("srv-Keep", "ep-1", 1_000),
                progress("srv-Ghost", "ep-2", 2_000),
            ),
        )
        val prepared = e.repo.prepareRestore(bytesOf(payload), "pw-pw".toCharArray())
        val validated = (prepared as PrepareResult.Prepared).validated
        val preview = e.repo.buildPreview(validated, RestoreStrategy.MERGE)

        assertEquals("新增 1", 1, preview.newServers)
        assertEquals("同源 1", 1, preview.identicalServers)
        assertEquals("冲突 1", 1, preview.conflictingServers)
        assertEquals(listOf("备份侧同ID"), preview.conflictServerNames)
        assertEquals("孤立 1", 1, preview.orphanProgressRecords)
        assertTrue("MERGE 不恢复偏好", !preview.preferencesWillRestore)
        assertEquals("预览零写入：无 apply 调用", 0, e.ds.applyCalls)
        assertEquals("预览零写入：服务器未动", 2, e.ds.servers.size)
        assertEquals("预览零写入：本机进度未动（载荷中的进度未写入）", 0, e.ds.progress.size)
        assertEquals("预览零写入：偏好未动", defaultPrefs.subtitleSizeSp, e.prefs.current.subtitleSizeSp)
    }

    // ---- MERGE 语义 ----

    @Test
    fun `merge keeps local prefs untouched`() = runTest(dispatcher) {
        val localPrefs = defaultPrefs.copy(subtitleSizeSp = 33)
        val e = env(prefs = localPrefs)
        val payload = payloadOf(
            servers = listOf(server("srv-New")),
            preferences = BackupDtos.PreferencesDto(subtitleSizeSp = 40),
        )
        val validated = (e.repo.prepareRestore(bytesOf(payload), "pw-pw".toCharArray()) as PrepareResult.Prepared).validated
        e.repo.restoreWithFreshPreview(validated, RestoreStrategy.MERGE, replaceConfirmed = false)
        assertEquals("合并模式不覆盖本机偏好", 33, e.prefs.current.subtitleSizeSp)
    }

    @Test
    fun `merge newer-wins on identical servers`() = runTest(dispatcher) {
        val local = server("srv-A")
        val e = env(
            servers = listOf(local),
            progress = listOf(
                progress("srv-A", "ep-local-newer", 5_000, updatedAt = 2_000),
                progress("srv-A", "ep-backup-newer", 1_000, updatedAt = 100),
            ),
        )
        val payload = payloadOf(
            servers = listOf(server("srv-A")),
            progress = listOf(
                progress("srv-A", "ep-local-newer", 9_000, updatedAt = 1_000),  // 本机较新 → 保留本机
                progress("srv-A", "ep-backup-newer", 8_000, updatedAt = 500),   // 备份较新 → 应用备份
                progress("srv-A", "ep-fresh", 7_000, updatedAt = 300),          // 本机没有 → 导入
            ),
        )
        val validated = (e.repo.prepareRestore(bytesOf(payload), "pw-pw".toCharArray()) as PrepareResult.Prepared).validated
        val result = e.repo.restoreWithFreshPreview(validated, RestoreStrategy.MERGE, replaceConfirmed = false)

        assertEquals("跳过同源服务器 1", 1, result.skippedExistingServers)
        val epLocal = e.ds.progress.first { it.itemId == "ep-local-newer" }
        assertEquals("本机较新记录保留", 5_000, epLocal.positionMs)
        val epBackup = e.ds.progress.first { it.itemId == "ep-backup-newer" }
        assertEquals("备份较新记录覆盖", 8_000, epBackup.positionMs)
        assertNotNull(e.ds.progress.firstOrNull { it.itemId == "ep-fresh" })
    }

    @Test
    fun `merge conflicting identity keeps local server and drops its progress`() = runTest(dispatcher) {
        val local = server("srv-C", name = "Local C", url = "https://local-c.example.com", username = "alice")
        val e = env(servers = listOf(local))
        val payload = payloadOf(
            servers = listOf(server("srv-C", name = "Backup C", url = "https://backup-c.example.com", username = "bob")),
            progress = listOf(progress("srv-C", "ep-1", 4_000)),
        )
        val validated = (e.repo.prepareRestore(bytesOf(payload), "pw-pw".toCharArray()) as PrepareResult.Prepared).validated
        val result = e.repo.restoreWithFreshPreview(validated, RestoreStrategy.MERGE, replaceConfirmed = false)

        assertEquals("冲突跳过 1", 1, result.conflictSkippedServers)
        val kept = e.ds.servers.single()
        assertEquals("本机版本保留", "Local C", kept.name)
        assertNull("同 ID 不同来源的进度不导入", e.ds.progress.firstOrNull { it.itemId == "ep-1" })
    }

    @Test
    fun `same server different account stays separate under merge`() = runTest(dispatcher) {
        val local = server("srv-A", name = "Account A", url = "https://same.example.com", username = null)
        val e = env(servers = listOf(local))
        val incoming = server("srv-A", name = "Account B", url = "https://same.example.com", username = "user-B")
        val payload = payloadOf(listOf(incoming))
        val validated = (e.repo.prepareRestore(bytesOf(payload), "pw-pw".toCharArray()) as PrepareResult.Prepared).validated
        e.repo.restoreWithFreshPreview(validated, RestoreStrategy.MERGE, replaceConfirmed = false)
        assertEquals("同址不同账号 = 冲突，不覆盖", "Account A", e.ds.servers.single().name)
    }

    // ---- REPLACE 语义 + 登录态隔离 ----

    @Test
    fun `replace without confirmation throws and writes nothing`() = runTest(dispatcher) {
        val e = env(servers = listOf(server("srv-A")))
        val payload = payloadOf(listOf(server("srv-A", name = "Backup A", url = "https://else.example.com")))
        val validated = (e.repo.prepareRestore(bytesOf(payload), "pw-pw".toCharArray()) as PrepareResult.Prepared).validated
        try {
            e.repo.restoreWithFreshPreview(validated, RestoreStrategy.REPLACE_SELECTED, replaceConfirmed = false)
            throw AssertionError("未确认的替换必须被 repository 侧防线拦截")
        } catch (expected: RestoreConfirmationRequiredException) {
            assertEquals("零写入", 0, e.ds.applyCalls)
        }
    }

    @Test
    fun `replace invalidates login only for identity-changed servers`() = runTest(dispatcher) {
        val identical = server("srv-Same", url = "https://same.example.com", username = "alice")
        val changed = server("srv-Moved", url = "https://old.example.com", username = "alice")
        val e = env(servers = listOf(identical, changed))
        val payload = payloadOf(
            servers = listOf(
                server("srv-Same", url = "https://same.example.com", username = "alice"),
                server("srv-Moved", url = "https://new.example.com", username = "alice"),
            ),
            preferences = BackupDtos.PreferencesDto(subtitleSizeSp = 42),
        )
        val validated = (e.repo.prepareRestore(bytesOf(payload), "pw-pw".toCharArray()) as PrepareResult.Prepared).validated
        val result = e.repo.restoreWithFreshPreview(validated, RestoreStrategy.REPLACE_SELECTED, replaceConfirmed = true)

        assertEquals("仅身份变化的同 ID 服务器清除旧登录态", listOf("srv-Moved"), e.invalidator.invalidated)
        assertEquals("覆盖 2", 2, result.overwrittenServers)
        assertEquals("替换模式恢复偏好", 42, e.prefs.current.subtitleSizeSp)
        assertEquals("地址已替换", "https://new.example.com", e.ds.servers.first { it.id == "srv-Moved" }.baseUrl)
    }

    // ---- 保护快照与回滚 ----

    @Test
    fun `mid-database failure rolls back to protective snapshot`() = runTest(dispatcher) {
        val local = server("srv-A", name = "Original A", url = "https://a.example.com")
        val localProgress = progress("srv-A", "ep-local", 1_000)
        val e = env(servers = listOf(local), progress = listOf(localProgress))
        e.ds.failAfterFirstServerOnCall = 1 // 第一次 apply = 恢复本体：写完第一个服务器后中断

        val payload = payloadOf(
            servers = listOf(server("srv-A", name = "Backup A", url = "https://backup-a.example.com"), server("srv-B")),
            progress = listOf(progress("srv-A", "ep-backup", 9_000)),
        )
        val validated = (e.repo.prepareRestore(bytesOf(payload), "pw-pw".toCharArray()) as PrepareResult.Prepared).validated

        try {
            e.repo.restoreWithFreshPreview(validated, RestoreStrategy.REPLACE_SELECTED, replaceConfirmed = true)
            throw AssertionError("中途失败应抛 RestoreFailedException")
        } catch (expected: RestoreFailedException) {
            assertTrue("回滚应已执行", expected.rolledBack)
            assertNull("回滚成功后日志清除", e.journal.entry)
            val restored = e.ds.servers.first { it.id == "srv-A" }
            assertEquals("本机服务器恢复原状", "Original A", restored.name)
            assertEquals("本机播放记录保留", 1_000, e.ds.progress.first { it.itemId == "ep-local" }.positionMs)
            assertNull("备份记录未写入", e.ds.progress.firstOrNull { it.itemId == "ep-backup" })
        }
    }

    @Test
    fun `legacy interruption without full image requires attention and writes nothing`() = runTest(dispatcher) {
        val e = env()
        val payload = payloadOf(listOf(server("srv-X", name = "From Backup")))
        val record = BackupDtos.RestorePlanRecord(
            strategy = "REPLACE_SELECTED",
            overwriteServerIds = listOf("srv-X"),
            idRemapping = mapOf("srv-X" to "srv-X"),
            includePreferences = false,
        )
        // 历史日志只有导出 DTO，无法证明完整本机 before-image；保留供人工处理。
        e.journal.begin(
            RestoreJournal.ActiveEntry(
                planId = "restore-interrupted",
                phase = RestoreJournal.Phase.PREPARING,
                payloadJson = BackupDtos.encodePayload(payload),
                planRecordJson = BackupDtos.encodePlanRecord(record),
                protectiveSnapshotJson = BackupDtos.encodePayload(payloadOf(emptyList())),
                includePreferences = false,
                startedAtEpochMs = 0,
            )
        )

        val outcome = e.repo.recoverInterruptedRestore()

        assertTrue(outcome is RecoveryOutcome.NeedsAttention)
        assertTrue(e.ds.servers.isEmpty())
        assertNotNull("旧日志缺全量保护快照，保留供处理", e.journal.entry)
    }

    @Test
    fun `legacy corrupt payload does not invent rollback evidence`() = runTest(dispatcher) {
        val local = server("srv-A", name = "Original")
        val e = env(servers = listOf(local))
        e.journal.begin(
            RestoreJournal.ActiveEntry(
                planId = "restore-broken",
                phase = RestoreJournal.Phase.DB_WRITTEN,
                payloadJson = "not-a-payload",
                planRecordJson = "{}",
                protectiveSnapshotJson = BackupDtos.encodePayload(payloadOf(listOf(local))),
                includePreferences = false,
                startedAtEpochMs = 0,
            )
        )

        val outcome = e.repo.recoverInterruptedRestore()

        assertTrue("旧日志缺完整保护快照，不猜测回滚", outcome is RecoveryOutcome.NeedsAttention)
        assertEquals("本机状态由保护快照恢复", "Original", e.ds.servers.single().name)
        assertNotNull(e.journal.entry)
    }

    @Test
    fun `restore refuses to stack on unresolved interruption`() = runTest(dispatcher) {
        val e = env()
        e.journal.begin(
            RestoreJournal.ActiveEntry(
                planId = "restore-stuck",
                phase = RestoreJournal.Phase.DB_WRITTEN,
                payloadJson = "corrupt",
                planRecordJson = "corrupt",
                protectiveSnapshotJson = "corrupt", // 回滚也失败 → NeedsAttention
                includePreferences = false,
                startedAtEpochMs = 0,
            )
        )
        val payload = payloadOf(listOf(server("srv-N")))
        val validated = (e.repo.prepareRestore(bytesOf(payload), "pw-pw".toCharArray()) as PrepareResult.Prepared).validated
        try {
            e.repo.restoreWithFreshPreview(validated, RestoreStrategy.MERGE, replaceConfirmed = false)
            throw AssertionError("未处理的中断必须阻止新的恢复")
        } catch (expected: RestoreFailedException) {
            assertEquals("拒绝叠加恢复：零写入", 0, e.ds.applyCalls)
        }
    }

    // ---- 端到端：播放记录与偏好往返 ----

    @Test
    fun `full roundtrip restores more than thirty progress records`() = runTest(dispatcher) {
        val e = env()
        val records = (1..35).map { progress("srv-Orig", "ep-$it", it * 1_000L, updatedAt = it.toLong()) }
        val payload = payloadOf(
            servers = listOf(server("srv-Orig")),
            progress = records,
            preferences = BackupDtos.PreferencesDto(
                defaultPlaybackSpeed = 1.75f,
                subtitleStyle = BackupDtos.SubtitleStyleDto(
                    textColor = 0xFF123456.toInt(), backgroundColor = 0x22334455.toInt(),
                    edgeType = 2, edgeColor = 0xFF654321.toInt(), textScale = 1.4f,
                    bottomPaddingFraction = 0.12f, applyEmbeddedStyles = false,
                ),
                gestures = BackupDtos.PlayerGesturesDto(
                    scrubEnabled = false,
                    doubleTapSeekBackwardEnabled = true, doubleTapSeekBackwardSeconds = 15,
                    doubleTapSeekForwardEnabled = true, doubleTapSeekForwardSeconds = 30,
                    longPressSpeedEnabled = false, longPressSpeedMin = 0.2f, longPressSpeedMax = 4.0f,
                    longPressDirectionalEnabled = false, longPressDefaultSpeed = 3.5f,
                ),
            ),
        )
        val bytes = bytesOf(payload)
        val validated = (e.repo.prepareRestore(bytes, "pw-pw".toCharArray()) as PrepareResult.Prepared).validated
        val result = e.repo.restoreWithFreshPreview(validated, RestoreStrategy.REPLACE_SELECTED, replaceConfirmed = true)

        assertEquals("35 条播放记录完整恢复", 35, result.restoredProgress)
        assertEquals(35, e.ds.progress.size)
        assertEquals("ep-17 位置保真", 17_000, e.ds.progress.first { it.itemId == "ep-17" }.positionMs)
        assertTrue("非默认偏好恢复", e.prefs.current.gestures.doubleTapSeekBackwardSeconds == 15 && !e.prefs.current.gestures.scrubEnabled)
    }

    @Test
    fun `exportBackup then prepareRestore roundtrip preserves server identity`() = runTest(dispatcher) {
        val e = env(
            servers = listOf(server("srv-A", name = "My Server", url = "https://a.example.com:8920", username = "alice")),
            progress = listOf(progress("srv-A", "ep-1", 42_000)),
        )
        val bytes = e.repo.exportBackup("pw".toCharArray(), "9.9.9-test")
        val prepared = e.repo.prepareRestore(bytes, "pw".toCharArray())
        val validated = (prepared as PrepareResult.Prepared).validated

        assertEquals("1 个服务器", 1, validated.servers.size)
        assertEquals("名称保真", "My Server", validated.servers[0].name)
        assertEquals("账号保真", "alice", validated.servers[0].username)
        assertEquals("播放记录 1 条", 1, validated.progress.size)
        assertEquals("基线版本", 1, validated.baselineFormatVersion)
        assertEquals("导出侧 appVersion 保真", "9.9.9-test", validated.baselineAppVersion)
    }

    @Test
    fun `non default prefs survive typed validation`() = runTest(dispatcher) {
        val e = env(prefs = defaultPrefs.copy(playbackEngineMode = PlaybackEngineMode.MPV))
        val bytes = e.repo.exportBackup("pw".toCharArray(), "1.0")
        val validated = (e.repo.prepareRestore(bytes, "pw".toCharArray()) as PrepareResult.Prepared).validated
        assertEquals("播放内核偏好类型化保真", PlaybackEngineMode.MPV, validated.preferences?.playbackEngineMode)
    }

    @Test
    fun `local source roundtrip excludes device permission uri and accepts no endpoints`() = runTest(dispatcher) {
        val e = env(servers = listOf(server("local", type = ServerType.LOCAL, url = "content://private/tree/FAKE-GRANT")))
        val bytes = e.repo.exportBackup("pw".toCharArray(), "1.0")
        val prepared = e.repo.prepareRestore(bytes, "pw".toCharArray()) as PrepareResult.Prepared
        assertEquals(ServerType.LOCAL, prepared.validated.servers.single().type)
        assertTrue(prepared.validated.servers.single().endpoints.isEmpty())
        assertFalse(BackupDtos.encodePayload(prepared.validated.payload).contains("FAKE-GRANT"))
    }
}
