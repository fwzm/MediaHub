package com.mediahub.feature.settings.backup

import com.mediahub.core.common.AppDispatchers
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.model.UserPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BackupViewModel 状态机回归（Phase 1I review）：
 * 导出链路闭合（空流=失败）、等待密码/执行区分、单任务与迟到结果作废、
 * 替换确认的 VM 侧防线、取消进入恢复协议、进入页面自动续作。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- fakes ----

    private class FakeBackupDataSource(
        initialServers: List<MediaServer> = emptyList(),
        initialProgress: List<PlaybackProgress> = emptyList(),
    ) : BackupDataSource {
        val servers = initialServers.toMutableList()
        val progress = initialProgress.toMutableList()
        var applyCalls = 0
            private set
        /** 非空时 applyRestorePlan 挂起直到放行（模拟长时间写库）。 */
        var applyGate: CompletableDeferred<Unit>? = null

        override suspend fun readSnapshot() = BackupSnapshot(servers.toList(), progress.toList())

        override suspend fun applyRestorePlan(plan: RestorePlan) {
            applyCalls++
            applyGate?.await()
            val after = materializeRestorePlan(readSnapshot(), plan)
            servers.clear()
            servers.addAll(after.servers)
            progress.clear()
            progress.addAll(after.progress)
        }
    }

    private open class FakePreferencesRepository(initial: UserPreferences) : UserPreferencesRepository {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
        override val flow = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    private class FakeRestoreJournal : RestoreJournal {
        var entry: RestoreJournal.ActiveEntry? = null
        override suspend fun begin(entry: RestoreJournal.ActiveEntry) { this.entry = entry }
        override suspend fun markPhase(planId: String, phase: RestoreJournal.Phase) {
            entry = entry?.takeIf { it.planId == planId }?.copy(phase = phase)
        }
        override suspend fun markFailed(planId: String, cause: String) {}
        override suspend fun read(): RestoreJournal.ActiveEntry? = entry
        override suspend fun clear(planId: String) {
            if (entry?.planId == planId) entry = null
        }
    }

    private class FakeLoginInvalidator : RestoreLoginInvalidator {
        val invalidated = mutableListOf<String>()
        override suspend fun invalidate(serverId: String) { invalidated += serverId }
    }

    private class FakeFileStore(
        var readResult: BackupFileStore.ReadResult =
            BackupFileStore.ReadResult.ReadFailed("未脚本化"),
        var writeResult: BackupFileStore.WriteResult = BackupFileStore.WriteResult.Written,
    ) : BackupFileStore {
        var writeCalls = 0
            private set
        var lastWrittenBytes: ByteArray? = null
            private set
        override suspend fun readBounded(uriString: String, maxBytes: Int) = readResult
        override suspend fun write(uriString: String, bytes: ByteArray): BackupFileStore.WriteResult {
            writeCalls++
            lastWrittenBytes = bytes
            return writeResult
        }
    }

    // ---- fixtures ----

    private val password = "test-pw".toCharArray()

    private class Env(
        val repo: BackupRepository,
        val ds: FakeBackupDataSource,
        val journal: FakeRestoreJournal,
        val invalidator: FakeLoginInvalidator,
        val snapshots: MemoryRestoreSnapshotStorage,
    )

    private fun env(servers: List<MediaServer> = emptyList()): Env {
        val ds = FakeBackupDataSource(servers)
        val journal = FakeRestoreJournal()
        val invalidator = FakeLoginInvalidator()
        val snapshots = MemoryRestoreSnapshotStorage()
        val repo = BackupRepository(
            ds,
            FakePreferencesRepository(UserPreferences()),
            journal,
            invalidator,
            AppDispatchers(io = dispatcher),
            snapshots,
        )
        return Env(repo, ds, journal, invalidator, snapshots)
    }

    private fun server(id: String, url: String = "https://$id.example.com") = MediaServer(
        id = id, name = "Server $id", type = ServerType.EMBY,
        createdAtEpochMs = 0,
        endpoints = listOf(ServerEndpoint(id = "", serverId = id, name = "主", url = url, isPrimary = true)),
    )

    /** 真实加密链路生成的备份字节（10k 迭代保持测试速度）。 */
    private fun backupBytes(e: Env, pw: CharArray = password): ByteArray {
        val payload = BackupDtos.BackupPayload(
            manifest = com.mediahub.core.common.backup.BackupFileFormat.Manifest(
                formatVersion = 1, minimumReaderVersion = 1,
                appVersion = "test", createdAtEpochMs = 0,
                includedSections = listOf("servers", "progress", "preferences"),
                recordCounts = mapOf("servers" to 1, "progress" to 0, "preferences" to 0),
            ),
            servers = listOf(
                BackupDtos.ServerDto(
                    backupId = "srv-B", name = "Backup Server", type = "EMBY",
                    endpoints = listOf(
                        BackupDtos.EndpointDto(name = "主", url = "https://srv-b.example.com", isPrimary = true, enabled = true, sortOrder = 0),
                    ),
                ),
            ),
        )
        return com.mediahub.core.common.backup.BackupSerializer.export(payload, pw, testIterations = 10_000)
    }

    // ---- 导出链路 ----

    @Test
    fun `rejected export while busy wipes both supplied passwords`() = runTest(dispatcher) {
        val e = env()
        val viewModel = BackupViewModel(e.repo, FakeFileStore())
        // 初始化恢复检查仍在队列中，本次导出必须被拒绝。
        val supplied = "busy-secret".toCharArray()
        val confirmation = supplied.copyOf()

        viewModel.startExport(supplied, confirmation, "1.0")
        advanceUntilIdle()

        assertTrue("拒绝的输入密码也应擦除", supplied.all { it == '\u0000' })
        assertTrue("拒绝的确认密码也应擦除", confirmation.all { it == '\u0000' })
        assertEquals(BackupUiState.Idle, viewModel.uiState.value)
        assertEquals("无业务写入", 0, e.ds.applyCalls)
    }

    @Test
    fun `export cancelled before coroutine starts still wipes passwords`() = runTest(dispatcher) {
        val e = env()
        val viewModel = BackupViewModel(e.repo, FakeFileStore())
        advanceUntilIdle()
        val supplied = "cancel-secret".toCharArray()
        val confirmation = supplied.copyOf()

        viewModel.startExport(supplied, confirmation, "1.0")
        viewModel.reset()
        advanceUntilIdle()

        assertTrue("协程启动前取消也应擦除密码", supplied.all { it == '\u0000' })
        assertTrue(confirmation.all { it == '\u0000' })
        assertEquals(BackupUiState.Idle, viewModel.uiState.value)
        assertEquals(0, e.ds.applyCalls)
    }

    @Test
    fun `duplicate export target result does not erase confirmed success`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore()
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()
        viewModel.startExport(password, password.copyOf(), "1.0")
        advanceUntilIdle()
        viewModel.onExportTargetPicked("content://target/first")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is BackupUiState.ExportSuccess)

        viewModel.onExportTargetPicked("content://target/duplicate")
        advanceUntilIdle()

        assertTrue("无待写数据的重复回调应忽略", viewModel.uiState.value is BackupUiState.ExportSuccess)
        assertEquals("不得重复写入", 1, fileStore.writeCalls)
    }

    @Test
    fun `export picker event is consumed once across route recreation and retry`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore(writeResult = BackupFileStore.WriteResult.StreamUnavailable)
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()
        viewModel.startExport(password, password.copyOf(), "1.0")
        advanceUntilIdle()

        val fileName = viewModel.takeExportFileNameForPicker()
        assertNotNull(fileName)
        assertEquals("重新组合不能重复消费事件", null, viewModel.takeExportFileNameForPicker())
        assertEquals(BackupUiState.AwaitingExportTarget, viewModel.uiState.value)
        viewModel.onExportTargetPicked("content://target/unavailable")
        advanceUntilIdle()

        viewModel.retryExportSave()
        assertEquals("显式重试生成一次新的选择事件", fileName, viewModel.takeExportFileNameForPicker())
        assertEquals(null, viewModel.takeExportFileNameForPicker())
        viewModel.onExportCancelled()
        assertEquals(BackupUiState.Idle, viewModel.uiState.value)
        assertEquals(null, viewModel.retryExportFileName)
        assertEquals("取消重试没有写文件", 1, fileStore.writeCalls)
    }

    @Test
    fun `failure to launch export picker remains retryable without reporting success`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore()
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()
        viewModel.startExport(password, password.copyOf(), "1.0")
        advanceUntilIdle()
        viewModel.takeExportFileNameForPicker()

        viewModel.onExportPickerFailed()

        val state = viewModel.uiState.value as BackupUiState.Error
        assertTrue(state.canRetryExportSave)
        assertEquals("启动失败不得写入或误报成功", 0, fileStore.writeCalls)
        viewModel.retryExportSave()
        assertNotNull(viewModel.takeExportFileNameForPicker())
    }

    @Test
    fun `export ready leads to picker and successful write`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore()
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()

        viewModel.startExport(password, password.copyOf(), "1.0")
        advanceUntilIdle()
        assertTrue("导出就绪应触发文件创建器流程", viewModel.uiState.value is BackupUiState.ExportReady)
        val fileName = (viewModel.uiState.value as BackupUiState.ExportReady).fileName
        assertTrue(fileName.endsWith(".mhb"))

        viewModel.onExportTargetPicked("content://target/backup.mhb")
        advanceUntilIdle()
        assertTrue("写入成功 → ExportSuccess", viewModel.uiState.value is BackupUiState.ExportSuccess)
        assertEquals(1, fileStore.writeCalls)
        assertTrue("写出的字节非空", fileStore.lastWrittenBytes!!.isNotEmpty())
    }

    @Test
    fun `unavailable output stream is a failure - not success`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore(writeResult = BackupFileStore.WriteResult.StreamUnavailable)
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()

        viewModel.startExport(password, password.copyOf(), "1.0")
        advanceUntilIdle()
        viewModel.onExportTargetPicked("content://target/backup.mhb")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("空流必须报失败", state is BackupUiState.Error)
        assertTrue("保留导出字节以供重试", (state as BackupUiState.Error).canRetryExportSave)
        assertEquals("重试文件名仍持有", true, viewModel.retryExportFileName != null)
    }

    @Test
    fun `write failure then retry succeeds`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore(writeResult = BackupFileStore.WriteResult.WriteFailed("磁盘已满"))
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()
        viewModel.startExport(password, password.copyOf(), "1.0")
        advanceUntilIdle()
        viewModel.onExportTargetPicked("content://x")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is BackupUiState.Error)

        fileStore.writeResult = BackupFileStore.WriteResult.Written
        viewModel.onExportTargetPicked("content://y")
        advanceUntilIdle()
        assertTrue("重试写入成功", viewModel.uiState.value is BackupUiState.ExportSuccess)
    }

    // ---- 文件读取 ----

    @Test
    fun `oversize file stops reading with typed error`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore(readResult = BackupFileStore.ReadResult.TooLarge)
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()

        viewModel.onRestoreFilePicked("content://big")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is BackupUiState.Error)
        assertTrue((state as BackupUiState.Error).message.contains("上限"))
    }

    @Test
    fun `read failure reports cause not empty backup`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore(readResult = BackupFileStore.ReadResult.ReadFailed("无法读取"))
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()
        viewModel.onRestoreFilePicked("content://x")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is BackupUiState.Error)
    }

    // ---- 密码与解密 ----

    @Test
    fun `awaiting password is distinct state - wrong password recovers to retry`() = runTest(dispatcher) {
        val e = env()
        val bytes = backupBytes(e)
        val fileStore = FakeFileStore(readResult = BackupFileStore.ReadResult.Read(bytes, "b.mhb"))
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()

        viewModel.onRestoreFilePicked("content://b")
        advanceUntilIdle()
        assertTrue("等待密码是独立状态", viewModel.uiState.value is BackupUiState.AwaitingPassword)

        viewModel.onPasswordEntered("wrong-pw".toCharArray())
        advanceUntilIdle()
        assertTrue("错误密码 → 类型化错误", viewModel.uiState.value is BackupUiState.Error)
        assertTrue((viewModel.uiState.value as BackupUiState.Error).canRetryRestorePassword)

        viewModel.retryRestorePassword()
        assertTrue("UI重试入口重新显示密码输入", viewModel.uiState.value is BackupUiState.AwaitingPassword)
        assertEquals("失败与重试仍零写入", 0, e.ds.applyCalls)

        viewModel.onPasswordEntered(password)
        advanceUntilIdle()
        assertTrue("错误后可重试进入预览", viewModel.uiState.value is BackupUiState.Preview)
    }

    @Test
    fun `late preview after cancel is discarded`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore(readResult = BackupFileStore.ReadResult.Read(backupBytes(e), "b.mhb"))
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()

        viewModel.onRestoreFilePicked("content://b")
        advanceUntilIdle()
        viewModel.onPasswordEntered(password) // 入队但尚未执行
        viewModel.cancelRestore()             // 取消：epoch 作废
        advanceUntilIdle()                    // 迟到的 Preview 到达

        assertEquals("取消后迟到结果不得重新显示预览", BackupUiState.Idle, viewModel.uiState.value)
    }

    // ---- 恢复确认与取消协议 ----

    @Test
    fun `strategy change rebuilds preview before confirmation can execute restore`() = runTest(dispatcher) {
        val e = env(listOf(server("srv-B", "https://local.example.com")))
        val fileStore = FakeFileStore(readResult = BackupFileStore.ReadResult.Read(backupBytes(e), "b.mhb"))
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()
        viewModel.onRestoreFilePicked("content://b")
        advanceUntilIdle()
        viewModel.onPasswordEntered(password)
        advanceUntilIdle()
        assertEquals(RestoreStrategy.MERGE, (viewModel.uiState.value as BackupUiState.Preview).preview.strategy)

        viewModel.changeRestoreStrategy(RestoreStrategy.REPLACE_SELECTED)
        assertEquals(BackupUiState.BuildingPreview, viewModel.uiState.value)
        viewModel.restore(RestoreStrategy.REPLACE_SELECTED, replaceConfirmed = true)
        assertEquals("新预览完成前确认不得写入", 0, e.ds.applyCalls)
        advanceUntilIdle()
        val state = viewModel.uiState.value as BackupUiState.Preview
        assertEquals(RestoreStrategy.REPLACE_SELECTED, state.preview.strategy)
        assertEquals("重新预览本身仍零写入", 0, e.ds.applyCalls)

        viewModel.restore(RestoreStrategy.REPLACE_SELECTED, replaceConfirmed = true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is BackupUiState.RestoreSuccess)
        assertEquals(1, e.ds.applyCalls)
        assertEquals("https://srv-b.example.com", e.ds.servers.single().endpoints.single().url)
        assertEquals("只有变化身份失效", listOf("srv-B"), e.invalidator.invalidated)
    }

    @Test
    fun `replace without confirmation blocked at viewmodel level`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore(readResult = BackupFileStore.ReadResult.Read(backupBytes(e), "b.mhb"))
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()
        viewModel.onRestoreFilePicked("content://b")
        advanceUntilIdle()
        viewModel.onPasswordEntered(password)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is BackupUiState.Preview)

        viewModel.restore(RestoreStrategy.REPLACE_SELECTED, replaceConfirmed = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is BackupUiState.Error)
        assertTrue((state as BackupUiState.Error).message.contains("确认"))
        assertEquals("未确认 → 零写入", 0, e.ds.applyCalls)
    }

    @Test
    fun `cancel mid-restore keeps recovery note and drops late success`() = runTest(dispatcher) {
        val e = env()
        val fileStore = FakeFileStore(readResult = BackupFileStore.ReadResult.Read(backupBytes(e), "b.mhb"))
        val viewModel = BackupViewModel(e.repo, fileStore)
        advanceUntilIdle()
        viewModel.onRestoreFilePicked("content://b")
        advanceUntilIdle()
        viewModel.onPasswordEntered(password)
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        e.ds.applyGate = gate
        try {
            viewModel.restore(RestoreStrategy.MERGE, replaceConfirmed = false)
            advanceUntilIdle()
            assertTrue("恢复进行中", viewModel.uiState.value is BackupUiState.Restoring)

            viewModel.cancelRestore()
            val state = viewModel.uiState.value
            assertTrue("取消不得静默回 Idle，须告知恢复协议接管", state is BackupUiState.Error)

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue("取消后不得显示成功结果", viewModel.uiState.value is BackupUiState.Error)
            assertNotNull("恢复日志保留（协议将在下次进入时接管）", e.journal.entry)
        } finally {
            gate.complete(Unit)
            viewModel.cancelRestore()
            advanceUntilIdle()
        }
    }

    @Test
    fun `re-entering page continues interrupted restore`() = runTest(dispatcher) {
        val e = env()
        val payload = BackupDtos.BackupPayload(
            manifest = com.mediahub.core.common.backup.BackupFileFormat.Manifest(
                formatVersion = 1, minimumReaderVersion = 1,
                appVersion = "test", createdAtEpochMs = 0,
                includedSections = listOf("servers", "progress", "preferences"),
                recordCounts = mapOf("servers" to 1, "progress" to 0, "preferences" to 0),
            ),
            servers = listOf(
                BackupDtos.ServerDto(
                    backupId = "srv-X", name = "Interrupted Restore", type = "EMBY",
                    endpoints = listOf(
                        BackupDtos.EndpointDto(name = "主", url = "https://srv-x.example.com", isPrimary = true, enabled = true, sortOrder = 0),
                    ),
                ),
            ),
        )
        val validated = (e.repo.prepareRestore(
            com.mediahub.core.common.backup.BackupSerializer.export(payload, password, testIterations = 10_000), password,
        ) as PrepareResult.Prepared).validated
        val frozen = e.repo.buildPreview(validated, RestoreStrategy.REPLACE_SELECTED).frozenPlan!!
        e.snapshots.save(frozen.plan.planId, frozen.images)
        e.journal.begin(
            RestoreJournal.ActiveEntry(
                planId = frozen.plan.planId,
                phase = RestoreJournal.Phase.PREPARING,
                payloadJson = BackupDtos.encodePayload(payload),
                planRecordJson = BackupDtos.encodePlanRecord(frozen.plan.record),
                protectiveSnapshotJson = "",
                protectiveSnapshotRef = frozen.plan.planId,
                includePreferences = false,
                startedAtEpochMs = 0,
            )
        )

        val viewModel = BackupViewModel(e.repo, FakeFileStore())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("进入页面应自动续作并告知", state is BackupUiState.Recovered)
        assertEquals("中断的恢复已前向完成", "Interrupted Restore", e.ds.servers.single().name)
        assertEquals(null, e.journal.entry)
    }
}
