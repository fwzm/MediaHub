package com.mediahub.feature.settings

import com.mediahub.core.common.sync.InMemorySourceSyncBaselineStore
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.ProfessionalInfoPreferences
import com.mediahub.model.UserPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        val state = MutableStateFlow(UserPreferences())
        override val flow: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }


    /** 固定结果的导出出口：用例只关心 ViewModel 把结果摆成什么状态。 */
    private class FixedDiagnosticsExportPort(private val outcome: DiagnosticsExportOutcome) : DiagnosticsExportPort {
        override suspend fun export(): DiagnosticsExportOutcome = outcome
    }

    /** 可挂起的导出出口：用来观察「第一次还没跑完时的第二次点击」。 */
    private class GatedDiagnosticsExportPort(
        private val outcome: DiagnosticsExportOutcome,
        private val gate: CompletableDeferred<Unit>,
    ) : DiagnosticsExportPort {
        var calls = 0
            private set

        override suspend fun export(): DiagnosticsExportOutcome {
            calls += 1
            gate.await()
            return outcome
        }
    }

    private fun newViewModel(
        repository: UserPreferencesRepository,
        diagnostics: DiagnosticsExportPort = FixedDiagnosticsExportPort(
            DiagnosticsExportOutcome.Exported("unused.txt", 0, 0),
        ),
    ) = SettingsViewModel(repository, InMemorySourceSyncBaselineStore(), diagnostics)
    /** preferences 是 WhileSubscribed 的 StateFlow：测试必须先订阅，上游才会被收集。
     *  订阅挂 backgroundScope（测试体结束时自动取消，不会卡住 runTest）。 */
    private fun CoroutineScope.subscribePreferences(viewModel: SettingsViewModel) = launch {
        viewModel.preferences.collect {}
    }

    @Test
    fun `initial preferences expose the professional default (expert mode on)`() = runTest(dispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.subscribePreferences(viewModel)
        runCurrent()

        assertTrue(viewModel.preferences.value.professionalInfo.expertMode)
        assertEquals(ProfessionalInfoPreferences.Default, viewModel.preferences.value.professionalInfo)
    }

    @Test
    fun `toggling professional info persists through the repository and reads back`() = runTest(dispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.subscribePreferences(viewModel)
        runCurrent()

        // 设置页开关路径：SettingsScreen 调 viewModel.update copy professionalInfo
        viewModel.update { p ->
            p.copy(professionalInfo = p.professionalInfo.copy(expertMode = false))
        }
        runCurrent()

        assertFalse(viewModel.preferences.value.professionalInfo.expertMode)
        assertFalse(repository.state.value.professionalInfo.expertMode)
        // 其他字段不被该更新触碰
        assertTrue(repository.state.value.enableHardwareDecoding)
        assertEquals(UserPreferences().playbackEngineMode, repository.state.value.playbackEngineMode)
    }

    @Test
    fun `toggling back on restores expert density`() = runTest(dispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = newViewModel(repository)
        backgroundScope.subscribePreferences(viewModel)
        runCurrent()

        viewModel.update { p ->
            p.copy(professionalInfo = p.professionalInfo.copy(expertMode = false))
        }
        runCurrent()
        viewModel.update { p ->
            p.copy(professionalInfo = p.professionalInfo.copy(expertMode = true))
        }
        runCurrent()

        assertTrue(repository.state.value.professionalInfo.expertMode)
    }

    @Test
    fun exportingDiagnosticsGoesThroughExportingAndLandsOnExported() = runTest(dispatcher) {
        val repository = FakeUserPreferencesRepository()
        val gate = CompletableDeferred<Unit>()
        val port = GatedDiagnosticsExportPort(DiagnosticsExportOutcome.Exported("cache/a.txt", 42, 3), gate)
        val viewModel = newViewModel(repository, port)
        backgroundScope.subscribePreferences(viewModel)
        runCurrent()

        assertEquals(DiagnosticsExportState.Idle, viewModel.diagnosticsState.value)
        assertNull(DiagnosticsExportText.line(DiagnosticsExportState.Idle))

        viewModel.exportDiagnostics()
        runCurrent()
        assertEquals(DiagnosticsExportState.Exporting, viewModel.diagnosticsState.value)
        assertEquals(DiagnosticsExportText.EXPORTING, DiagnosticsExportText.line(viewModel.diagnosticsState.value))

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DiagnosticsExportState.Exported("cache/a.txt", 42, 3), viewModel.diagnosticsState.value)
        assertEquals(1, port.calls)
        assertEquals("已导出 3 条日志（42 字节）：cache/a.txt", DiagnosticsExportText.line(viewModel.diagnosticsState.value))
    }

    @Test
    fun aSecondTapWhileTheFirstExportIsStillRunningExportsOnlyOnce() = runTest(dispatcher) {
        val repository = FakeUserPreferencesRepository()
        val gate = CompletableDeferred<Unit>()
        val port = GatedDiagnosticsExportPort(DiagnosticsExportOutcome.Exported("cache/a.txt", 42, 3), gate)
        val viewModel = newViewModel(repository, port)

        viewModel.exportDiagnostics()
        runCurrent()
        viewModel.exportDiagnostics()
        runCurrent()
        assertEquals(1, port.calls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, port.calls)
        assertEquals(DiagnosticsExportState.Exported("cache/a.txt", 42, 3), viewModel.diagnosticsState.value)

        // 跑完之后再点可以再导一次：单飞限制的是同时，不是只能一次。
        viewModel.exportDiagnostics()
        advanceUntilIdle()
        assertEquals(2, port.calls)
    }

    @Test
    fun aFailedExportReportsTheReasonAndTheCleanedCause() = runTest(dispatcher) {
        val repository = FakeUserPreferencesRepository()
        val port = FixedDiagnosticsExportPort(
            DiagnosticsExportOutcome.Failed("diagnostics-write-failed", "not a directory"),
        )
        val viewModel = newViewModel(repository, port)

        viewModel.exportDiagnostics()
        advanceUntilIdle()

        assertEquals(
            DiagnosticsExportState.Failed("diagnostics-write-failed", "not a directory"),
            viewModel.diagnosticsState.value,
        )
        assertEquals(
            "导出失败：diagnostics-write-failed（原因：not a directory）",
            DiagnosticsExportText.line(viewModel.diagnosticsState.value),
        )
        assertEquals(
            "导出失败：diagnostics-write-failed",
            DiagnosticsExportText.line(DiagnosticsExportState.Failed("diagnostics-write-failed", null)),
        )
    }

}
