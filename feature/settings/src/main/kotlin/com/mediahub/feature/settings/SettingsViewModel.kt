package com.mediahub.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.sync.ISourceSyncBaselineStore
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「导出诊断日志」按钮的状态（W6-110）。Idle = 还没点过，界面据此不显示结果行。 */
sealed interface DiagnosticsExportState {
    /** 还没点过。 */
    object Idle : DiagnosticsExportState

    /** 正在写文件。 */
    object Exporting : DiagnosticsExportState

    /** 已写出：绝对路径、字节数、条目数。 */
    data class Exported(val path: String, val bytes: Int, val entries: Int) : DiagnosticsExportState

    /** 写不出去：稳定原因码 +（可能为 null 的）净化后原因。 */
    data class Failed(val reason: String, val causeMessage: String?) : DiagnosticsExportState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesStore: UserPreferencesRepository,
    /**
     * 已配对设备页的同步基线出口（W6-80）：忘记一台设备时要连同该对端的基线一起清掉，
     * 否则同一 deviceId 重新配对后会被当成「已经同步过」，对端真实变更被静默跳过。
     * 设置页只是把这一个出口转交给 PairedDeviceRoute。
     */
    val syncBaselineStore: ISourceSyncBaselineStore,
    /**
     * 诊断日志导出出口（W6-110）：日志环形缓冲 → 文件。
     * 设置页只把这一个出口转交给「导出诊断日志」按钮；导出内容与脱敏在 core:logging 里。
     */
    private val diagnosticsExport: DiagnosticsExportPort,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesStore.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    private val _diagnosticsState = MutableStateFlow<DiagnosticsExportState>(DiagnosticsExportState.Idle)

    /** 「导出诊断日志」的状态；界面只读这里。 */
    val diagnosticsState: StateFlow<DiagnosticsExportState> = _diagnosticsState.asStateFlow()

    private var diagnosticsJob: Job? = null

    fun update(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { preferencesStore.update(transform) }
    }

    fun resetPlayerVisualEffects() {
        viewModelScope.launch { preferencesStore.resetPlayerVisualEffects() }
    }

    /**
     * 导出诊断日志（W6-110）。单飞：上一次还在跑时再点不会叠加导出。
     * Exporting → 出口跑完 → Exported / Failed；对外的句子由 [DiagnosticsExportText] 统一给。
     */
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
