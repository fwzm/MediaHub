package com.mediahub.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType

/** 添加媒体库。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerRoute(
    onDone: (MediaServer) -> Unit,
    onBack: () -> Unit,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
            ServerTypeGrid(
                selected = state.selectedType,
                onSelect = viewModel::selectType,
            )

            if (state.selectedType != ServerType.LOCAL) {
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
                placeholder = { Text(state.selectedType.label) },
                singleLine = true,
            )

            if (state.selectedType != ServerType.LOCAL) {
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
                    label = { Text("密码（可选，加密存储）") },
                    singleLine = true,
                )
            }

            state.testResult?.let { result ->
                Text(
                    text = result.message ?: if (result.ok) "连接正常" else "连接失败",
                    color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.isTesting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.isTesting) "测试中…" else "测试连接")
                }
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

@Composable
private fun ServerTypeGrid(
    selected: ServerType,
    onSelect: (ServerType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ServerTypeOptions.chunked(2).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { option ->
                    FilterChip(
                        selected = selected == option.type,
                        onClick = { if (option.enabled) onSelect(option.type) },
                        enabled = option.enabled,
                        label = {
                            Text(if (option.enabled) option.type.label else "${option.type.label}（${option.description}）")
                        },
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
