package com.mediahub.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.sync.ISourceSyncBaselineStore
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.ProgressStore
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

/** 「清除播放记录」的状态（条目 63）。Idle = 还没点过。 */
sealed interface PlaybackHistoryClearState {
    object Idle : PlaybackHistoryClearState
    object Clearing : PlaybackHistoryClearState

    /** 已清除：真实删除条数（空表为 0）。 */
    data class Cleared(val count: Int) : PlaybackHistoryClearState

    /** 清除失败：只说"失败"，不把数据库细节抛给界面。 */
    object Failed : PlaybackHistoryClearState
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
    /**
     * 本地播放记录（条目 63）：设置页提供用户可见的「清除播放记录」入口。
     * 只走 ProgressStore 的清空契约（幂等、返回条数），不接触远端进度。
     */
    private val progressStore: ProgressStore,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesStore.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    private val _diagnosticsState = MutableStateFlow<DiagnosticsExportState>(DiagnosticsExportState.Idle)

    /** 「导出诊断日志」的状态；界面只读这里。 */
    val diagnosticsState: StateFlow<DiagnosticsExportState> = _diagnosticsState.asStateFlow()

    private var diagnosticsJob: Job? = null

    private val _historyClearState =
        MutableStateFlow<PlaybackHistoryClearState>(PlaybackHistoryClearState.Idle)

    /** 「清除播放记录」的状态；界面只读这里。 */
    val historyClearState: StateFlow<PlaybackHistoryClearState> = _historyClearState.asStateFlow()

    private var historyClearJob: Job? = null

    /**
     * 条目 63：清空全部本地播放记录。
     *
     * - 单飞：上一次还在跑时重复点击不叠加；
     * - 幂等：空表返回 0，界面显示"已清除 0 条"；
     * - 不抛错：失败只落到 [PlaybackHistoryClearState.Failed]，页面继续可用。
     */
    fun clearPlaybackHistory() {
        if (historyClearJob?.isActive == true) return
        historyClearJob = viewModelScope.launch {
            _historyClearState.value = PlaybackHistoryClearState.Clearing
            _historyClearState.value = try {
                PlaybackHistoryClearState.Cleared(progressStore.clearAll())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaybackHistoryClearState.Failed
            }
        }
    }

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
