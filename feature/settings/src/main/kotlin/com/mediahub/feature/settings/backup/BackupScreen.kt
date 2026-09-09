package com.mediahub.feature.settings.backup

import android.content.Context
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 同步与备份页（Phase 1I review 重写）：
 * - 导出链路闭合：生成 → 自动拉起文件创建器 → 后台写入（空流=失败）→ 结果/重试；
 * - 替换恢复必须显式勾选确认；执行中按钮全部禁用；
 * - 页面不做任何 ContentResolver IO，全部经 ViewModel + BackupFileStore。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRoute(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appVersion = remember(context) { backupAppVersion(context) }
    val previewState = state as? BackupUiState.Preview

    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    val restoreStrategy = previewState?.preview?.strategy ?: RestoreStrategy.MERGE
    var replaceConfirmed by remember(previewState) { mutableStateOf(false) }

    // 密码输入不跨阶段残留：离开对应阶段即清空页面输入
    LaunchedEffect(state) {
        if (state !is BackupUiState.AwaitingPassword && state !is BackupUiState.Idle && state !is BackupUiState.Error) {
            restorePassword = ""
        }
        if (state is BackupUiState.RestoreSuccess || state is BackupUiState.ExportSuccess || state is BackupUiState.Idle) {
            restorePassword = ""
            exportPassword = ""
            exportPasswordConfirm = ""
            replaceConfirmed = false
        }
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            viewModel.onExportTargetPicked(uri.toString())
        } else {
            viewModel.onExportCancelled()
        }
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onRestoreFilePicked(uri.toString())
        }
        // uri == null → 用户取消，不做任何事
    }

    // 导出生成就绪 → 自动拉起保存位置选择器（每次进入 ExportReady 仅一次）
    LaunchedEffect(state) {
        if (state !is BackupUiState.ExportReady) return@LaunchedEffect
        val fileName = viewModel.takeExportFileNameForPicker() ?: return@LaunchedEffect
        try {
            createDocLauncher.launch(fileName)
        } catch (_: Exception) {
            viewModel.onExportPickerFailed()
        }
    }

    val busy = state is BackupUiState.Exporting ||
        state is BackupUiState.WritingExport ||
        state is BackupUiState.Decrypting ||
        state is BackupUiState.Restoring ||
        state is BackupUiState.Recovering ||
        state is BackupUiState.BuildingPreview ||
        state is BackupUiState.AwaitingExportTarget

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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
            )
            OutlinedTextField(
                value = exportPasswordConfirm,
                onValueChange = { exportPasswordConfirm = it },
                label = { Text("确认备份密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
            )
            Text(
                "备份不含密码、Token 等登录凭据；忘记备份密码将无法恢复数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val password = exportPassword.toCharArray()
                    val confirmation = exportPasswordConfirm.toCharArray()
                    exportPassword = ""
                    exportPasswordConfirm = ""
                    viewModel.startExport(
                        password,
                        confirmation,
                        appVersion,
                    )
                },
                // Error 态同样可重新发起导出（错误不应锁死导出入口）
                enabled = (state == BackupUiState.Idle || state is BackupUiState.Error) && exportPassword.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state is BackupUiState.Exporting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("正在生成备份…")
                } else {
                    Text("导出备份")
                }
            }
            if (state is BackupUiState.WritingExport) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("正在写入备份文件…")
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ---- 从文件恢复 ----
            Text("从文件恢复", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = { openDocLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = state == BackupUiState.Idle || state is BackupUiState.Error,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("选择备份文件")
            }

            // ---- 恢复密码输入（与执行中状态严格区分） ----
            when (val s = state) {
                is BackupUiState.AwaitingPassword -> {
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text("备份密码（${s.fileName}）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Button(
                        onClick = {
                            val password = restorePassword.toCharArray()
                            restorePassword = ""
                            viewModel.onPasswordEntered(password)
                        },
                        enabled = restorePassword.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("解密并预览")
                    }
                }
                is BackupUiState.Decrypting -> if (s.fileName.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("正在解密并验证…")
                    }
                }
                else -> {}
            }

            // ---- 恢复预览 ----
            if (state is BackupUiState.Recovering || state is BackupUiState.BuildingPreview) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(if (state is BackupUiState.Recovering) "正在检查上次恢复状态…" else "正在更新恢复预览…")
                }
            }
            if (previewState != null) {
                SectionHeader("恢复预览")
                Text("备份应用版本：${previewState.preview.appVersion}")
                Text(
                    "备份创建时间：" + java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.getDefault(),
                    ).format(java.util.Date(previewState.preview.createdAtEpochMs))
                )
                Text("新增媒体源：${previewState.preview.newServers}")
                Text("已有同源媒体源${if (restoreStrategy == RestoreStrategy.MERGE) "（保留本机）" else ""}：${previewState.preview.identicalServers}")
                if (previewState.preview.conflictingServers > 0) {
                    Text(
                        "同 ID 不同来源冲突：${previewState.preview.conflictingServers} 个" +
                            "（${previewState.preview.conflictServerNames.joinToString("、")}）" +
                            "——本机版本保留，其播放记录不导入",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("播放记录：${previewState.preview.progressToWrite} 条（${if (restoreStrategy == RestoreStrategy.MERGE) "合并" else "替换"}口径）")
                if (previewState.preview.orphanProgressRecords > 0) {
                    Text(
                        "无法关联到备份内媒体源的播放记录：${previewState.preview.orphanProgressRecords} 条（将跳过）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val prefsLabel = when {
                    previewState.preview.preferencesContained && restoreStrategy == RestoreStrategy.REPLACE_SELECTED -> "将恢复（仅替换模式恢复设置）"
                    previewState.preview.preferencesContained -> "备份包含设置，但合并模式保留本机设置"
                    else -> "备份不包含设置"
                }
                Text("应用设置：$prefsLabel")
                Text("备份格式版本：v${previewState.preview.baselineFormatVersion}")

                Spacer(Modifier.height(8.dp))
                Text("恢复策略", style = MaterialTheme.typography.titleSmall)

                RestoreStrategyRadio(
                    label = "合并（保留本机已有数据）",
                    selected = restoreStrategy == RestoreStrategy.MERGE,
                    onSelect = { viewModel.changeRestoreStrategy(RestoreStrategy.MERGE) },
                )
                RestoreStrategyRadio(
                    label = "替换所选数据（覆盖已有）",
                    selected = restoreStrategy == RestoreStrategy.REPLACE_SELECTED,
                    onSelect = { viewModel.changeRestoreStrategy(RestoreStrategy.REPLACE_SELECTED) },
                )
                if (restoreStrategy == RestoreStrategy.REPLACE_SELECTED) {
                    Text(
                        "替换将按 ID 覆盖本机媒体源及其播放记录；地址或账号变化的服务器将清除旧登录态（需重新登录）。此操作不可撤销。",
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

                // 替换策略必须显式确认；执行中（Restoring 替换 Preview）自动禁用
                Button(
                    onClick = { viewModel.restore(restoreStrategy, replaceConfirmed) },
                    enabled = restoreStrategy == RestoreStrategy.MERGE || replaceConfirmed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (restoreStrategy == RestoreStrategy.MERGE) "合并恢复" else "替换恢复")
                }
            }
            if (state is BackupUiState.Restoring) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("正在恢复…")
                }
                OutlinedButton(
                    onClick = { viewModel.cancelRestore() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("取消")
                }
            }

            // ---- 结果 ----
            when (val s = state) {
                is BackupUiState.RestoreSuccess -> {
                    Text("恢复完成", style = MaterialTheme.typography.titleSmall)
                    Text("新增媒体源：${s.result.addedServers}")
                    Text("覆盖媒体源：${s.result.overwrittenServers}")
                    Text("保留本机同源媒体源：${s.result.skippedExistingServers}")
                    Text("冲突跳过：${s.result.conflictSkippedServers}")
                    Text("恢复播放记录：${s.result.restoredProgress}")
                    Text("已清理登录信息的媒体源：${s.result.loginsInvalidated} 个")
                    Text("应用设置：${if (s.result.preferencesRestored) "已恢复" else "未包含/未恢复"}")
                    Text(
                        "被覆盖且地址或账号变化的服务器需重新登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is BackupUiState.Recovered -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is BackupUiState.ExportSuccess -> {
                    Text(s.message, color = MaterialTheme.colorScheme.primary)
                    Button(
                        onClick = { viewModel.discardExport() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("完成")
                    }
                }
                is BackupUiState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    if (s.canRetryRestorePassword) {
                        Button(onClick = { viewModel.retryRestorePassword() }) { Text("重新输入备份密码") }
                    }
                    if (s.canRetryExportSave) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.retryExportSave() }) {
                                Text("重新选择保存位置")
                            }
                            OutlinedButton(onClick = { viewModel.discardExport() }) {
                                Text("放弃导出")
                            }
                        }
                    }
                }
                else -> {}
            }
            if (state is BackupUiState.RestoreSuccess || state is BackupUiState.Recovered) {
                Button(onClick = { viewModel.reset() }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
            }
            if (state is BackupUiState.AwaitingPassword || state is BackupUiState.Preview) {
                OutlinedButton(onClick = { viewModel.cancelRestore() }, modifier = Modifier.fillMaxWidth()) { Text("取消恢复") }
            }
        }
    }
}

/** 读取安装包元数据，版本更新后备份自动携带实际构建版本。 */
internal fun backupAppVersion(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return info.versionName ?: info.versionCode.toString()
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
