package com.mediahub.feature.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerRoute(
    onDone: (MediaServer) -> Unit,
    onBack: () -> Unit,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val descriptor = state.selectedDescriptor
    val context = LocalContext.current
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreePermission(context, uri)
                .onSuccess { viewModel.updateLocalTree(uri.toString(), treeDisplayName(uri)) }
                .onFailure { viewModel.reportDirectoryGrantError("无法保存目录授权：${it.message}") }
        }
    }

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
            ProviderGrid(
                providers = state.providers,
                selectedProviderId = state.selectedProviderId,
                onSelect = viewModel::selectProvider,
            )

            descriptor?.let {
                Text(
                    text = it.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (descriptor?.category == ProviderCategory.LOCAL_STORAGE) {
                OutlinedButton(
                    onClick = { directoryPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.localTreeUri == null) "选择媒体目录" else "重新选择目录")
                }
                state.localTreeName?.let { name ->
                    Text(
                        text = "已选择：$name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
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
                placeholder = { Text(descriptor?.displayName.orEmpty()) },
                singleLine = true,
            )

            if (
                descriptor?.authMethod == AuthMethod.USERNAME_PASSWORD ||
                descriptor?.authMethod == AuthMethod.BASIC
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
                    label = { Text("密码（仅进入加密凭据库）") },
                    visualTransformation = PasswordVisualTransformation(),
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
                    enabled = !state.isTesting && descriptor?.isSelectable == true,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.isTesting) "测试中…" else "测试连接")
                }
                Button(
                    onClick = { viewModel.save(onSaved = onDone) },
                    enabled = !state.isSaving && descriptor?.isSelectable == true,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.isSaving) "保存中…" else "保存")
                }
            }
        }
    }
}

@Composable
private fun ProviderGrid(
    providers: List<ProviderDescriptor>,
    selectedProviderId: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        providers.chunked(2).forEach { rowProviders ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowProviders.forEach { descriptor ->
                    val suffix = when (descriptor.status) {
                        ProviderStatus.AVAILABLE -> ""
                        ProviderStatus.EXPERIMENTAL -> " · 实验性"
                        ProviderStatus.COMING_SOON -> " · 即将支持"
                    }
                    FilterChip(
                        selected = selectedProviderId == descriptor.providerId,
                        onClick = { if (descriptor.isSelectable) onSelect(descriptor.providerId) },
                        enabled = descriptor.isSelectable,
                        label = { Text(descriptor.displayName + suffix) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowProviders.size == 1) Text("", modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun persistTreePermission(context: Context, uri: Uri): Result<Unit> = runCatching {
    val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
        context.contentResolver.takePersistableUriPermission(uri, readWrite)
    } catch (_: SecurityException) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun treeDisplayName(uri: Uri): String = runCatching {
    DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { "已选目录" }
}.getOrDefault("已选目录")
