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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
                title = { Text("添加媒体库") },
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
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::updateBaseUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("服务器地址") },
                        placeholder = { Text("http://192.168.1.100:8096") },
                        singleLine = true,
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
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::updatePassword,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Text(
                        text = "密码仅用于本次登录，不会保存在设备中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.testResult?.let { result ->
                Text(
                    text = result.message ?: if (result.ok) "连接正常" else "连接失败",
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
                        Text(if (state.isLoggingIn) "登录中…" else "登录并添加")
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
