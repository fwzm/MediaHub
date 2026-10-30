package com.mediahub.feature.settings

import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.UserPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Repository : UserPreferencesRepository {
        override val flow: Flow<UserPreferences> = MutableStateFlow(UserPreferences())
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) = Unit
    }

    private class GatedPort(
        private val gate: CompletableDeferred<Unit>,
    ) : DiagnosticsExportPort {
        val calls = AtomicInteger()
        override suspend fun export(): DiagnosticsExportOutcome {
            calls.incrementAndGet()
            gate.await()
            return DiagnosticsExportOutcome.Exported("cache/a.txt", 42, 3)
        }
    }

    @Test
    fun export_reports_progress_then_success_and_does_not_overlap() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val port = GatedPort(gate)
        val viewModel = SettingsViewModel(Repository(), port)

        assertEquals(DiagnosticsExportState.Idle, viewModel.diagnosticsState.value)
        assertNull(DiagnosticsExportText.line(DiagnosticsExportState.Idle))
        viewModel.exportDiagnostics()
        runCurrent()
        viewModel.exportDiagnostics()
        runCurrent()
        assertEquals(DiagnosticsExportState.Exporting, viewModel.diagnosticsState.value)
        assertEquals(1, port.calls.get())

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DiagnosticsExportState.Exported("cache/a.txt", 42, 3), viewModel.diagnosticsState.value)
        assertEquals("已导出 3 条日志（42 字节）：cache/a.txt", DiagnosticsExportText.line(viewModel.diagnosticsState.value))
    }

    @Test
    fun a_failed_export_keeps_the_stable_reason_and_clean_cause() = runTest(dispatcher) {
        val port = object : DiagnosticsExportPort {
            override suspend fun export(): DiagnosticsExportOutcome =
                DiagnosticsExportOutcome.Failed("diagnostics-write-failed", "not a directory")
        }
        val viewModel = SettingsViewModel(Repository(), port)

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
    }
}
