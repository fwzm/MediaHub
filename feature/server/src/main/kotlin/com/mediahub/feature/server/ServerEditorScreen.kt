package com.mediahub.feature.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.ServerIcon
import com.mediahub.model.MediaServer

/** Server Editor 页面：编辑已有媒体源。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorRoute(
    onBack: () -> Unit,
    onRelogin: (MediaServer) -> Unit,
    onDeleted: () -> Unit,
    viewModel: ServerEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showIconDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.saveIcon(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑媒体源") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save(onBack) }, enabled = !state.isSaving) {
                        if (state.isSaving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("保存")
                    }
                },
            )
        },
    ) { padding ->
        val server = state.server
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            server == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(state.error ?: "找不到媒体源", style = MaterialTheme.typography.bodyMedium)
            }

            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            ) {
                // 头部：图标 + 名称 + 类型
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ServerIcon(
                        icon = state.icon,
                        label = server.displayName,
                        modifier = Modifier.size(56.dp).clickable { showIconDialog = true },
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            server.type.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showIconDialog = true }) { Text("更换图标") }
                }

                // 错误/消息提示
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                state.message?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                SectionHeader("基本信息")
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::updateNote,
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )

                SectionHeader("连接")
                ServerAddressField(
                    value = state.baseUrl,
                    onValueChange = viewModel::updateBaseUrl,
                    preferHttps = state.preferHttps,
                    onHttpsChange = viewModel::toggleHttps,
                    resolvedUrl = state.resolvedUrl,
                    errorMessage = state.addressError,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.testResult?.let { r ->
                        Text(
                            if (r.ok) "连接成功" else (r.message ?: "连接失败"),
                            color = if (r.ok) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = viewModel::testConnection, enabled = !state.isTesting) {
                        if (state.isTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("测试连接")
                    }
                }

                SectionHeader("线路质量测试")
                Button(
                    onClick = viewModel::testMediaQuality,
                    enabled = !state.isMediaTesting,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    if (state.isMediaTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("测试线路质量")
                }
                state.mediaQualityResult?.let { qr ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (qr.error != null) {
                            Text("线路不可用", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                            val err = qr.error; InfoRow("错误", err ?: "未知")
                        } else {
                            val score = qr.score()
                            val stars = "★".repeat(score / 20) + "☆".repeat(5 - score / 20)
                            Text("播放质量  $stars $score", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            qr.apiLatencyMs?.let { InfoRow("API 延迟", "${it}ms") }
                            qr.mediaFirstByteMs?.let { InfoRow("媒体首包", "${it}ms") }
                            qr.downloadSpeedBytesPerSec?.let {
                                InfoRow("下载速度", "%.1f MB/s".format(it / (1024.0 * 1024.0)))
                            }
                            InfoRow("Range", if (qr.supportsRange) "✓ 支持" else "✗ 不支持")
                            qr.httpStatus?.let { InfoRow("HTTP", "$it") }
                            qr.protocol?.let { InfoRow("协议", it) }
                        }
                        InfoRow("最后测试", java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(qr.testedAt)))
                    }
                }

                SectionHeader("账号")
                InfoRow("用户名", server.username ?: "未设置")
                InfoRow("状态", if (state.loggedIn) "已登录" else "未登录")
                TextButton(onClick = { onRelogin(server) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("重新登录")
                }

                SectionHeader("偏好")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("设为默认媒体源", Modifier.weight(1f))
                    Switch(checked = server.isDefault, onCheckedChange = { if (it) viewModel.setDefault() })
                }

                HorizontalDivider(Modifier.padding(vertical = 24.dp))
                TextButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !state.isDeleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
                ) {
                    if (state.isDeleting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("删除媒体源")
                }
            }
        }
    }

    if (showIconDialog) {
        AlertDialog(
            onDismissRequest = { showIconDialog = false },
            title = { Text("更换图标") },
            text = {
                Column {
                    TextButton(onClick = { viewModel.useBuiltinIcon(); showIconDialog = false }) { Text("使用 Emby 默认图标") }
                    TextButton(onClick = {
                        showIconDialog = false
                        iconPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("从相册选择") }
                    TextButton(onClick = { viewModel.removeIcon(); showIconDialog = false }) { Text("移除自定义图标") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showIconDialog = false }) { Text("取消") } },
        )
    }

    if (showDeleteDialog && state.server != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除“" + state.server!!.displayName + "”？") },
            text = { Text("该媒体源的登录信息和本地观看进度也会被删除。") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; viewModel.delete(onDeleted) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}