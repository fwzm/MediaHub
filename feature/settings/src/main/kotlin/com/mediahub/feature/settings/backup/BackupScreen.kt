package com.mediahub.feature.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 同步与备份页（Phase 1I-A：本地备份与还原）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRoute(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var restoreStrategy by remember { mutableStateOf(RestoreStrategy.MERGE) }
    var replaceConfirmed by remember { mutableStateOf(false) }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && state is BackupUiState.ExportReady) {
            val exportState = state as BackupUiState.ExportReady
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(exportState.bytes)
                }
                viewModel.onExportWritten(success = true)
            } catch (e: Exception) {
                viewModel.onExportWritten(success = false)
            }
        } else if (uri == null) {
            viewModel.onExportCancelled()
        }
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > BackupSerializerLimits.MAX_FILE_BYTES) {
                        viewModel.onFileSelected(
                            ByteArray(0), // 触发 oversize 错误
                            fileName = uri.lastPathSegment ?: "backup.mhb",
                        )
                    } else {
                        viewModel.onFileSelected(bytes, uri.lastPathSegment ?: "backup.mhb")
                    }
                }
            } catch (e: Exception) {
                viewModel.onFileSelected(ByteArray(0), "read-error")
            }
        }
        // uri == null → 用户取消 → 不做任何事，回 Idle
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步与备份") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 本地备份 ----
            SectionHeader("本地备份")

            Text("导出备份", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = exportPassword,
                onValueChange = { exportPassword = it },
                label = { Text("备份密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = exportPasswordConfirm,
                onValueChange = { exportPasswordConfirm = it },
                label = { Text("确认备份密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                "备份不含密码、Token 等登录凭据；忘记备份密码将无法恢复数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { viewModel.startExport(exportPassword.toCharArray(), exportPasswordConfirm.toCharArray(), "0.1.0-alpha.1") },
                enabled = state == BackupUiState.Idle && exportPassword.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state == BackupUiState.Exporting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("导出备份")
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Text("从文件恢复", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = {
                    openDocLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                enabled = state == BackupUiState.Idle || state is BackupUiState.Error,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("选择备份文件")
            }

            // ---- 恢复密码输入 ----
            if (state is BackupUiState.Decrypting) {
                OutlinedTextField(
                    value = restorePassword,
                    onValueChange = { restorePassword = it },
                    label = { Text("备份密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    onClick = { viewModel.onPasswordEntered(restorePassword.toCharArray()) },
                    enabled = restorePassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("解密")
                }
            }

            // ---- 恢复预览 ----
            val previewState = state as? BackupUiState.Preview
            if (previewState != null) {
                SectionHeader("恢复预览")
                Text("备份应用版本：${previewState.preview.appVersion}")
                Text("备份创建时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(previewState.preview.createdAtEpochMs))}")
                Text("新增媒体源：${previewState.preview.newServers}")
                Text("已有媒体源：${previewState.preview.existingServers}")
                Text("播放记录：${previewState.preview.progressRecords} 条")
                Text("应用设置：${if (previewState.preview.preferencesRestore) "将恢复" else "不包含"}")

                Spacer(Modifier.height(8.dp))
                Text("恢复策略", style = MaterialTheme.typography.titleSmall)

                RestoreStrategyRadio(
                    label = "合并（保留本机已有数据）",
                    selected = restoreStrategy == RestoreStrategy.MERGE,
                    onSelect = { restoreStrategy = RestoreStrategy.MERGE; replaceConfirmed = false },
                )
                RestoreStrategyRadio(
                    label = "替换所选数据（覆盖已有）",
                    selected = restoreStrategy == RestoreStrategy.REPLACE_SELECTED,
                    onSelect = { restoreStrategy = RestoreStrategy.REPLACE_SELECTED; replaceConfirmed = false },
                )
                if (restoreStrategy == RestoreStrategy.REPLACE_SELECTED) {
                    Text(
                        "替换将覆盖本机同 ID 媒体源和偏好，此操作不可撤销。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = replaceConfirmed,
                            onCheckedChange = { replaceConfirmed = it },
                        )
                        Text("我确认要替换所选数据")
                    }
                }

                Button(
                    onClick = { viewModel.restore(restoreStrategy) },
                    enabled = !replaceConfirmed || (restoreStrategy == RestoreStrategy.MERGE) || replaceConfirmed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state is BackupUiState.Restoring) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (restoreStrategy == RestoreStrategy.MERGE) "合并恢复" else "替换恢复")
                    }
                }
            }

            // ---- 结果 ----
            when (val s = state) {
                is BackupUiState.RestoreSuccess -> {
                    Text("恢复完成", style = MaterialTheme.typography.titleSmall)
                    Text("新增媒体源：${s.result.addedServers}")
                    Text("跳过已有媒体源：${s.result.skippedExistingServers}")
                    Text("恢复播放记录：${s.result.restoredProgress}")
                    Text("应用设置：${if (s.result.preferencesRestored) "已恢复" else "未包含"}")
                    Text("恢复的媒体源需重新登录。")
                }
                is BackupUiState.ExportSuccess -> Text(s.message, color = MaterialTheme.colorScheme.primary)
                is BackupUiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                else -> {}
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun RestoreStrategyRadio(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

/** SAF 读取大小限制（与 BackupSerializer.MAX_FILE_BYTES 一致）。 */
object BackupSerializerLimits {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
}
