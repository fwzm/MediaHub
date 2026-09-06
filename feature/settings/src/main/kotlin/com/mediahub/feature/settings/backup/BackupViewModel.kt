package com.mediahub.feature.settings.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.common.backup.BackupSerializer
import com.mediahub.core.database.prefs.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 备份/恢复操作状态机（密码与文件字节仅存 VM 内存，不入 SavedStateHandle）。 */
sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Exporting : BackupUiState
    data class ExportReady(val bytes: ByteArray, val suggestedFileName: String) : BackupUiState
    data class Decrypting(val fileName: String) : BackupUiState
    data class Preview(
        val payload: BackupDtos.BackupPayload,
        val preview: RestorePreview,
        val fileName: String,
    ) : BackupUiState
    data class Restoring(val strategy: RestoreStrategy) : BackupUiState
    data class RestoreSuccess(val result: RestoreResult) : BackupUiState
    data class ExportSuccess(val message: String) : BackupUiState
    data class Error(val message: String) : BackupUiState
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 密码与文件字节仅存 VM 内存，不入 SavedStateHandle/日志/持久化。 */
    private var pendingPassword: CharArray? = null
    private var pendingBytes: ByteArray? = null
    private var pendingFileName: String? = null

    fun reset() {
        pendingPassword = null
        pendingBytes = null
        pendingFileName = null
        _uiState.value = BackupUiState.Idle
    }

    // ---- 导出 ----

    fun startExport(password: CharArray, passwordConfirm: CharArray, appVersion: String) {
        if (password.contentEquals(passwordConfirm).not()) {
            _uiState.value = BackupUiState.Error("两次输入的密码不一致")
            return
        }
        if (password.isEmpty()) {
            _uiState.value = BackupUiState.Error("请设置备份密码")
            return
        }
        pendingPassword = password.copyOf()
        _uiState.value = BackupUiState.Exporting
        viewModelScope.launch {
            try {
                val bytes = backupRepository.exportBackup(password, appVersion)
                pendingBytes = bytes
                val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val fileName = "MediaHub-backup-$ts.mhb"
                _uiState.value = BackupUiState.ExportReady(bytes, fileName)
            } catch (e: Exception) {
                _uiState.value = BackupUiState.Error("导出失败：${e.message}")
            }
        }
    }

    /** SAF 写入完成后的确认（由 Screen 调用）。 */
    fun onExportWritten(success: Boolean) {
        val bytes = pendingBytes
        pendingBytes = null
        pendingPassword = null
        if (success && bytes != null) {
            _uiState.value = BackupUiState.ExportSuccess("备份已导出")
        } else {
            _uiState.value = BackupUiState.Error("备份写入失败")
        }
    }

    fun onExportCancelled() {
        pendingBytes = null
        pendingPassword = null
        _uiState.value = BackupUiState.Idle
    }

    // ---- 恢复 ----

    fun onFileSelected(bytes: ByteArray, fileName: String) {
        pendingBytes = bytes
        pendingFileName = fileName
        // 需要用户输入密码——由 Screen 弹密码对话框后调 onPasswordEntered
        _uiState.value = BackupUiState.Decrypting(fileName)
    }

    fun onPasswordEntered(password: CharArray) {
        val bytes = pendingBytes ?: return
        val fileName = pendingFileName ?: "backup"
        _uiState.value = BackupUiState.Decrypting(fileName)
        viewModelScope.launch {
            decryptAndPreview(bytes, password, fileName)
        }
    }

    private fun decryptAndPreview(bytes: ByteArray, password: CharArray, fileName: String) {
        viewModelScope.launch {
            val result = backupRepository.importBackup(bytes, password)
            when (result) {
                is BackupSerializer.ImportResult.Ok -> {
                    pendingPassword = password.copyOf()
                    val preview = backupRepository.buildPreview(result.payload)
                    _uiState.value = BackupUiState.Preview(result.payload, preview, fileName)
                }
                is BackupSerializer.ImportResult.AuthenticationFailed ->
                    _uiState.value = BackupUiState.Error(result.cause)
                is BackupSerializer.ImportResult.Corrupted ->
                    _uiState.value = BackupUiState.Error("备份文件损坏：${result.reason}")
                is BackupSerializer.ImportResult.VersionTooNew ->
                    _uiState.value = BackupUiState.Error("备份版本过新（${result.fileVersion}），请升级应用")
                is BackupSerializer.ImportResult.NotABackupFile ->
                    _uiState.value = BackupUiState.Error("不是有效的 MediaHub 备份文件")
            }
        }
    }

    fun restore(strategy: RestoreStrategy) {
        val state = _uiState.value
        if (state !is BackupUiState.Preview) return
        val pw = pendingPassword ?: return
        val payload = state.payload
        _uiState.value = BackupUiState.Restoring(strategy)
        viewModelScope.launch {
            try {
                val result = backupRepository.restore(payload, strategy)
                pendingPassword = null
                pendingBytes = null
                _uiState.value = BackupUiState.RestoreSuccess(result)
            } catch (e: Exception) {
                _uiState.value = BackupUiState.Error("恢复失败：${e.message}")
            }
        }
    }

    fun cancelRestore() {
        pendingBytes = null
        pendingPassword = null
        pendingFileName = null
        _uiState.value = BackupUiState.Idle
    }
}
