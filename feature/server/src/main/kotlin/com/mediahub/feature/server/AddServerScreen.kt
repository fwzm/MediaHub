package com.mediahub.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderDescriptor

/** 添加媒体库：数据源类型从 Registry 动态读取（ADR-015）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerRoute(
    onDone: (MediaServer) -> Unit,
    onBack: () -> Unit,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val providers = viewModel.availableProviders
    val selected = providers.firstOrNull { it.id == state.selectedDescriptorId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (viewModel.isReauthorizing()) {
                            if (viewModel.isLoadingExisting()) "加载中…" else "重新登录"
                        } else {
                            "添加媒体库"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("选择类型", style = MaterialTheme.typography.titleMedium)
            ProviderTypeGrid(
                providers = providers,
                selectedId = state.selectedDescriptorId,
                onSelect = viewModel::selectProvider,
            )

            selected?.let { descriptor ->
                if (descriptor.serverType != ServerType.LOCAL) {
                    ServerAddressField(
                        value = state.baseUrl,
                        onValueChange = viewModel::updateBaseUrl,
                        preferHttps = state.preferHttps,
                        onHttpsChange = viewModel::toggleHttps,
                        resolvedUrl = state.resolvedUrl,
                        errorMessage = state.addressError,
                    )
                }

                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称") },
                    placeholder = { Text(descriptor.displayName) },
                    singleLine = true,
                )

                if (descriptor.serverType != ServerType.LOCAL &&
                    descriptor.authMethod != com.mediahub.provider.api.AuthMethod.NONE
                ) {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::updateUsername,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("用户名（可选）") },
                        singleLine = true,
                        keyboardOptions = ServerTextInputOptions,
                    )
                    // 显示/隐藏密码：纯本地 UI 状态，不影响提交的明文；
                    // 默认隐藏（与既有行为一致），用户可主动切换为明文以便核对自己输入。
                    var passwordVisible by rememberSaveable { mutableStateOf(false) }
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::updatePassword,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = ServerTextInputOptions.copy(
                            keyboardType = if (passwordVisible) {
                                KeyboardType.Text
                            } else {
                                KeyboardType.Password
                            },
                        ),
                        // 图标只用 icons-core 里确定存在的 Lock，避免为两个图标引入
                        // material-icons-extended（该依赖会显著增大 APK）。
                        // 语义由 contentDescription 明确表达，视觉上配合文案切换。
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                if (passwordVisible) {
                                    // 明文态：用文字提示"隐藏"，比图标更不易误解
                                    Text(
                                        text = "隐藏",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "显示密码",
                                    )
                                }
                            }
                        },
                    )
                    Text(
                        text = if (descriptor.serverType == ServerType.WEBDAV) {
                            "WebDAV 密码加密保存在本机，仅用于此媒体源"
                        } else {
                            "密码仅用于本次登录，不会保存在设备中"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.testResult?.let { result ->
                Text(
                    // P3：失败时优先展示**按 errorCode 得出的可操作指引**，
                    // 而不是只把自由文本 message 丢给用户（此前 errorCode 从未被消费）。
                    text = if (result.ok) {
                        result.message ?: "连接正常"
                    } else {
                        listOfNotNull(result.message, result.toActionableHint()).joinToString(String(charArrayOf(10.toChar())))
                    },
                    color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.loginError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.isTesting && !state.isLoggingIn,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.isTesting) "测试中…" else "测试连接")
                }
                if (selected != null && selected.authMethod != com.mediahub.provider.api.AuthMethod.NONE) {
                    Button(
                        onClick = { viewModel.loginAndSave(onSaved = onDone) },
                        enabled = !state.isLoggingIn && !state.isTesting,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (state.isLoggingIn) "登录中…"
                            else if (viewModel.isReauthorizing()) "重新登录"
                            else "登录并添加"
                        )
                    }
                } else {
                    Button(
                        onClick = { viewModel.save(onSaved = onDone) },
                        enabled = !state.isSaving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.isSaving) "保存中…" else "保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderTypeGrid(
    providers: List<ProviderDescriptor>,
    selectedId: String,
    onSelect: (ProviderDescriptor) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        providers.chunked(2).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { descriptor ->
                    FilterChip(
                        selected = selectedId == descriptor.id,
                        onClick = { onSelect(descriptor) },
                        label = { Text(descriptor.displayName) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowOptions.size == 1) {
                    Text("", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
