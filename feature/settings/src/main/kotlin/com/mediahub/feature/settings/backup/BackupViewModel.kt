package com.mediahub.feature.settings.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.backup.BackupDtos
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 备份/恢复操作状态机（Phase 1I review 重写）。
 *
 * - 等待密码（[BackupUiState.AwaitingPassword]）与执行中（Decrypting/WritingExport/Restoring）
 *   严格区分；执行中入口全部拒绝（单任务：[currentJob] 活跃时忽略新操作）；
 * - 每次操作持有递增 [opEpoch]，迟到结果（取消后返回）按 epoch 丢弃，
 *   禁止旧结果重新显示预览或成功；
 * - 密码 CharArray 在操作结束（成功/失败/取消）后尽力擦除，不入 SavedStateHandle/日志/持久化；
 * - 导出链路：生成 → [BackupUiState.ExportReady]（UI 据此启动文件创建器）→ 用户选定位置
 *   → 后台写入（空流/异常均失败）→ 成功或带重试入口的错误。
 */
sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Exporting : BackupUiState
    data class ExportReady(val bytes: ByteArray, val fileName: String) : BackupUiState
    data object WritingExport : BackupUiState
    data class ExportSuccess(val message: String) : BackupUiState
    data class AwaitingPassword(val fileName: String) : BackupUiState
    data class Decrypting(val fileName: String) : BackupUiState
    data class Preview(
        val validated: ValidatedRestore,
        val preview: RestorePreview,
        val fileName: String,
    ) : BackupUiState
    data class Restoring(val strategy: RestoreStrategy) : BackupUiState
    data class RestoreSuccess(val result: RestoreResult) : BackupUiState
    /** 进入页面时发现上次恢复中断并已按协议处理。 */
    data class Recovered(val message: String) : BackupUiState
    data class Error(
        val message: String,
        /** true = 导出字节仍持有，可重新选择保存位置重试。 */
        val canRetryExportSave: Boolean = false,
    ) : BackupUiState
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    private val backupFileStore: BackupFileStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 递增操作代号：取消/新操作后，旧协程的迟到结果一律丢弃。 */
    private var opEpoch = 0L
    private var currentJob: Job? = null

    // 待写字节/待解密字节仅存 VM 内存；密码不落地（用后即擦）。
    private var pendingExportBytes: ByteArray? = null
    private var pendingExportFileName: String? = null
    private var pendingRestoreBytes: ByteArray? = null
    private var pendingRestoreFileName: String? = null

    init {
        // 进入页面即执行恢复协议：上次中断的恢复前向继续或回滚
        currentJob = viewModelScope.launch {
            val outcome = backupRepository.recoverInterruptedRestore()
            when (outcome) {
                is RecoveryOutcome.Continued -> _uiState.value =
                    BackupUiState.Recovered(
                        "检测到上次恢复中断，已自动续作完成：" +
                            "媒体源 ${outcome.result.addedServers + outcome.result.overwrittenServers} 个、" +
                            "播放记录 ${outcome.result.restoredProgress} 条"
                    )
                RecoveryOutcome.RolledBack ->
                    _uiState.value = BackupUiState.Recovered("检测到上次恢复中断，已回滚到恢复前状态")
                RecoveryOutcome.CompletedEarlier ->
                    _uiState.value = BackupUiState.Recovered("上次恢复已完成（完成标记此前未落盘）")
                is RecoveryOutcome.NeedsAttention ->
                    _uiState.value = BackupUiState.Error("上次恢复中断未能自动处理：${outcome.reason}")
                RecoveryOutcome.NothingToRecover -> {}
            }
        }
    }

    private fun beginOperation(): Long {
        currentJob?.cancel()
        opEpoch += 1
        return opEpoch
    }

    private fun isBusy(): Boolean = currentJob?.isActive == true

    private fun setStateIfCurrent(epoch: Long, state: BackupUiState) {
        if (epoch == opEpoch) _uiState.value = state
    }

    private fun CharArray.wipe() = fill('\u0000')

    /** 返回待重试的导出文件名（供 UI 重新拉起文件创建器）。 */
    val retryExportFileName: String? get() = pendingExportFileName

    fun reset() {
        cancelRestore()
        discardExport()
    }

    // ---- 导出 ----

    fun startExport(password: CharArray, passwordConfirm: CharArray, appVersion: String) {
        if (isBusy()) return
        if (password.isEmpty()) {
            _uiState.value = BackupUiState.Error("请设置备份密码")
            password.wipe(); passwordConfirm.wipe()
            return
        }
        if (!password.contentEquals(passwordConfirm)) {
            _uiState.value = BackupUiState.Error("两次输入的密码不一致")
            password.wipe(); passwordConfirm.wipe()
            return
        }
        val epoch = beginOperation()
        _uiState.value = BackupUiState.Exporting
        currentJob = viewModelScope.launch {
            try {
                val bytes = backupRepository.exportBackup(password, appVersion)
                val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val fileName = "MediaHub-backup-$ts.mhb"
                pendingExportBytes = bytes
                pendingExportFileName = fileName
                setStateIfCurrent(epoch, BackupUiState.ExportReady(bytes, fileName))
            } catch (e: ExportRejectedException) {
                setStateIfCurrent(epoch, BackupUiState.Error(e.message ?: "导出被拒绝"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStateIfCurrent(epoch, BackupUiState.Error("导出失败：${e.message}"))
            } finally {
                password.wipe()
                passwordConfirm.wipe()
            }
        }
    }

    /** 用户在文件创建器中选定了保存位置。空流、写入异常都报告失败，绝不误报成功。 */
    fun onExportTargetPicked(uriString: String) {
        val bytes = pendingExportBytes ?: run {
            _uiState.value = BackupUiState.Idle
            return
        }
        if (isBusy()) return
        val epoch = beginOperation()
        _uiState.value = BackupUiState.WritingExport
        currentJob = viewModelScope.launch {
            when (val result = backupFileStore.write(uriString, bytes)) {
                is BackupFileStore.WriteResult.Written -> {
                    pendingExportBytes = null
                    pendingExportFileName = null
                    setStateIfCurrent(epoch, BackupUiState.ExportSuccess("备份已导出"))
                }
                is BackupFileStore.WriteResult.StreamUnavailable ->
                    setStateIfCurrent(
                        epoch,
                        BackupUiState.Error("保存位置不可写（未能建立输出流），备份未写入", canRetryExportSave = true),
                    )
                is BackupFileStore.WriteResult.WriteFailed ->
                    setStateIfCurrent(epoch, BackupUiState.Error(result.cause, canRetryExportSave = true))
            }
        }
    }

    /** 用户取消保存位置选择。 */
    fun onExportCancelled() = discardExport()

    fun discardExport() {
        pendingExportBytes = null
        pendingExportFileName = null
        if (!isBusy()) _uiState.value = BackupUiState.Idle
    }

    // ---- 恢复 ----

    /** 用户选定备份文件：有界读取，超限/空文件/读取失败均类型化报错。 */
    fun onRestoreFilePicked(uriString: String) {
        if (isBusy()) return
        val epoch = beginOperation()
        _uiState.value = BackupUiState.Decrypting("") // 占位：读取中（文件名未知）
        currentJob = viewModelScope.launch {
            when (val result = backupFileStore.readBounded(uriString)) {
                is BackupFileStore.ReadResult.Read -> {
                    pendingRestoreBytes = result.bytes
                    pendingRestoreFileName = result.fileName
                    setStateIfCurrent(epoch, BackupUiState.AwaitingPassword(result.fileName))
                }
                BackupFileStore.ReadResult.TooLarge ->
                    setStateIfCurrent(epoch, BackupUiState.Error("文件超过 ${BackupSerializerLimits.MAX_FILE_BYTES / 1024 / 1024}MB 上限，已停止读取"))
                BackupFileStore.ReadResult.Empty ->
                    setStateIfCurrent(epoch, BackupUiState.Error("所选文件为空"))
                is BackupFileStore.ReadResult.ReadFailed ->
                    setStateIfCurrent(epoch, BackupUiState.Error(result.cause))
            }
        }
    }

    fun onPasswordEntered(password: CharArray) {
        val bytes = pendingRestoreBytes
        val fileName = pendingRestoreFileName ?: "backup"
        if (bytes == null) {
            password.wipe()
            _uiState.value = BackupUiState.Idle
            return
        }
        if (isBusy()) {
            password.wipe()
            return
        }
        val epoch = beginOperation()
        _uiState.value = BackupUiState.Decrypting(fileName)
        currentJob = viewModelScope.launch {
            try {
                val prepared = backupRepository.prepareRestore(bytes, password)
                val newState = when (prepared) {
                    is PrepareResult.Prepared -> {
                        val preview = backupRepository.buildPreview(prepared.validated, RestoreStrategy.MERGE)
                        BackupUiState.Preview(prepared.validated, preview, fileName)
                    }
                    is PrepareResult.Rejected -> BackupUiState.Error(prepared.reason)
                }
                setStateIfCurrent(epoch, newState)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStateIfCurrent(epoch, BackupUiState.Error("解密失败：${e.message}"))
            } finally {
                password.wipe()
            }
        }
    }

    /**
     * 应用恢复。ViewModel 侧校验：替换策略必须携带显式确认；
     * repository 内部还有第二道防线（与按钮状态无关）。
     */
    fun restore(strategy: RestoreStrategy, replaceConfirmed: Boolean) {
        val state = _uiState.value
        if (state !is BackupUiState.Preview) return
        if (isBusy()) return
        if (strategy == RestoreStrategy.REPLACE_SELECTED && !replaceConfirmed) {
            _uiState.value = BackupUiState.Error("替换所选数据需要先勾选确认")
            return
        }
        val epoch = beginOperation()
        _uiState.value = BackupUiState.Restoring(strategy)
        currentJob = viewModelScope.launch {
            try {
                val result = backupRepository.restore(state.validated, strategy, replaceConfirmed)
                pendingRestoreBytes = null
                pendingRestoreFileName = null
                setStateIfCurrent(epoch, BackupUiState.RestoreSuccess(result))
            } catch (e: RestoreFailedException) {
                val rollbackNote = if (e.rolledBack) "已回滚到恢复前状态" else "数据已保留中断现场，重新进入本页将自动续作"
                setStateIfCurrent(epoch, BackupUiState.Error("恢复失败：${e.message}（$rollbackNote）"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStateIfCurrent(epoch, BackupUiState.Error("恢复失败：${e.message}"))
            }
        }
    }

    /** 取消当前恢复流程。已开始写入的现场由 repository 恢复协议兜底（前向完成或回滚）。 */
    fun cancelRestore() {
        val wasRestoring = _uiState.value is BackupUiState.Restoring
        currentJob?.cancel()
        currentJob = null
        opEpoch += 1 // 迟到结果一律作废
        pendingRestoreBytes = null
        pendingRestoreFileName = null
        _uiState.value = if (wasRestoring) {
            // 恢复可能已部分写入：不能静默回 Idle，须告知恢复协议将接管
            BackupUiState.Error("已取消恢复。若恢复已开始写入，重新进入本页将自动续作或回滚")
        } else {
            BackupUiState.Idle
        }
    }
}
