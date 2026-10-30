package com.mediahub.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface DiagnosticsExportState {
    data object Idle : DiagnosticsExportState
    data object Exporting : DiagnosticsExportState
    data class Exported(val path: String, val bytes: Int, val entries: Int) : DiagnosticsExportState
    data class Failed(val reason: String, val causeMessage: String?) : DiagnosticsExportState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesStore: UserPreferencesRepository,
    private val diagnosticsExport: DiagnosticsExportPort = object : DiagnosticsExportPort {
        override suspend fun export(): DiagnosticsExportOutcome =
            DiagnosticsExportOutcome.Failed("diagnostics-unavailable", null)
    },
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesStore.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    private val _diagnosticsState = MutableStateFlow<DiagnosticsExportState>(DiagnosticsExportState.Idle)
    val diagnosticsState: StateFlow<DiagnosticsExportState> = _diagnosticsState.asStateFlow()
    private var diagnosticsJob: Job? = null

    fun update(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { preferencesStore.update(transform) }
    }

    fun resetPlayerVisualEffects() {
        viewModelScope.launch { preferencesStore.resetPlayerVisualEffects() }
    }

    fun exportDiagnostics() {
        if (diagnosticsJob?.isActive == true) return
        diagnosticsJob = viewModelScope.launch {
            _diagnosticsState.value = DiagnosticsExportState.Exporting
            _diagnosticsState.value = when (val outcome = diagnosticsExport.export()) {
                is DiagnosticsExportOutcome.Exported ->
                    DiagnosticsExportState.Exported(outcome.path, outcome.bytes, outcome.entries)
                is DiagnosticsExportOutcome.Failed ->
                    DiagnosticsExportState.Failed(outcome.reason, outcome.causeMessage)
            }
        }
    }
}
