package com.mediahub.feature.settings.sync

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 导入页的稳定 testTag 表（docs/android-sync-ui.md 同步登记）。
 *
 * 多条目页面上 allow/skip 会重复出现，所以每个条目的卡片另有 sync.entry:<key> 标签，
 * UI 测试用 hasAnyAncestor 把按钮限定到某一条，避免"点了第一行却断言第二行"。
 */
object SourceSyncImportTags {
    const val SCREEN = "sync.import.screen"
    const val PICK = "sync.import.pick"
    const val PREVIEW = "sync.import.preview"
    const val APPLY = "sync.apply"
    const val RESULT = "sync.apply.result"
    const val BLOCKED = "sync.apply.blocked"
    const val ENTRY_ALLOW = "sync.entry.allow"
    const val ENTRY_SKIP = "sync.entry.skip"

    /** 条目当前决策的一行回执（未决定 / 已允许 / 已跳过 / 不可允许原因）。 */
    const val ENTRY_STATE = "sync.entry.state"

    /** W6-H：结果页每个来源的「补录并验证」按钮。 */
    const val VERIFY = "sync.source.verify"

    /** 补录凭据对话框本体。 */
    const val VERIFY_DIALOG = "sync.source.verify.dialog"

    /** 密码输入框（视觉上被 PasswordVisualTransformation 遮蔽，语义上带 Password 标记）。 */
    const val VERIFY_INPUT = "sync.source.verify.input"

    const val VERIFY_CONFIRM = "sync.source.verify.confirm"
    const val VERIFY_CANCEL = "sync.source.verify.cancel"

    /** 单个来源的三态状态行（需补录凭据 / 已验证 / 验证失败：原因）。 */
    const val VERIFY_STATE = "sync.source.verify.state"

    fun entry(key: String): String = "sync.entry:" + key

    /** 每个来源的验证区块（同页多条时要能把状态行限定到某一条）。 */
    fun verifyRow(serverId: String): String = "sync.source.verify.row:" + serverId

    // ---- W6-27：从 Windows 接收 ----

    /** 接收区块本体（地址 / 端口 / 挑战 / 接收按钮）。 */
    const val RECEIVE = "sync.receive"

    const val RECEIVE_HOST = "sync.receive.host"

    const val RECEIVE_PORT = "sync.receive.port"

    const val RECEIVE_CHALLENGE = "sync.receive.challenge"

    const val RECEIVE_ACTION = "sync.receive.action"

    /** 接收结果行（已收下 / 接收失败：码 / 信封被拒：码）。 */
    const val RECEIVE_STATE = "sync.receive.state"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSyncImportRoute(
    onBack: () -> Unit,
    viewModel: SourceSyncImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val verification by viewModel.verificationState.collectAsStateWithLifecycle()
    val receiveState by viewModel.receiveState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val loaded = readEnvelope(context, uri)
            if (loaded != null) {
                viewModel.loadEnvelope(loaded.first, loaded.second)
            }
        }
    }
    SourceSyncImportScreen(
        state = state,
        onPick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
        onPreview = viewModel::preview,
        onDecide = viewModel::decide,
        onApply = viewModel::apply,
        onBack = onBack,
        verification = verification,
        onVerifyRequest = viewModel::openVerificationDialog,
        onVerifyDismiss = viewModel::dismissVerificationDialog,
        onVerifyConfirm = viewModel::verify,
        receiveState = receiveState,
        onReceive = viewModel::receive,
    )
}

/**
 * 无状态导入页：整页只由 [state] / [verification] 与回调驱动。
 *
 * 这样 UI 测试可以直接渲染本函数（不需要 Hilt、不需要真实仓库），
 * 交互证据与逻辑证据因此能分开取。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSyncImportScreen(
    state: SourceSyncImportState,
    onPick: () -> Unit,
    onPreview: () -> Unit,
    onDecide: (String, SourceSyncEntryDecision) -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit,
    /** W6-H：结果页的逐源验证状态；默认空（既有调用点无需改动）。 */
    verification: SourceVerificationViewState = SourceVerificationViewState(),
    onVerifyRequest: (String) -> Unit = {},
    onVerifyDismiss: () -> Unit = {},
    onVerifyConfirm: (String, String) -> Unit = { _, _ -> },
    /** W6-27：从 Windows 接收区块的状态；默认 Idle（既有调用点与 UI 测试无需改动）。 */
    receiveState: SourceSyncReceiveState = SourceSyncReceiveState.Idle,
    onReceive: (SourceSyncReceiveForm) -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.testTag(SourceSyncImportTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text("从文件导入来源") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "选择另一台设备导出的信封文件（.json 或 .mhsync 的明文信封）。" +
                    "预览不会写入任何数据。",
                style = MaterialTheme.typography.bodyMedium,
            )
            ReceiveSection(state = receiveState, onReceive = onReceive)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SourceSyncImportTags.PICK),
                ) {
                    Text("选择文件")
                }
                Button(
                    onClick = onPreview,
                    enabled = state.canPreview && !state.isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SourceSyncImportTags.PREVIEW),
                ) {
                    Text("预览")
                }
            }
            if (!state.fileName.isNullOrBlank()) {
                Text(
                    "已选：" + state.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("处理中…", style = MaterialTheme.typography.bodySmall)
                }
            }

            state.preview?.let { preview ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    SourceSyncImportLogic.summaryLine(preview),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "来源设备：" + preview.deviceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    SourceSyncImportCopy.ALLOW_RISK,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preview.buckets.forEach { bucket ->
                    if (bucket.entries.isNotEmpty()) {
                        BucketSection(
                            bucket = bucket,
                            interactive = state.status == SourceSyncImportStatus.PREVIEWED,
                            onDecide = onDecide,
                        )
                    }
                }
                Button(
                    onClick = onApply,
                    enabled = state.canApply && !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SourceSyncImportTags.APPLY),
                ) {
                    Text("应用导入")
                }
            }

            val blocked = state.blockedReason
            if (blocked != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SourceSyncImportTags.BLOCKED),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            SourceSyncImportCopy.blockedText(blocked),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        state.blockedMessage?.takeIf { it.isNotBlank() }?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            state.applied?.let { applied ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SourceSyncImportTags.RESULT),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("导入结果", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "已导入 " + applied.appliedCount +
                                " · 需确认 " + applied.requiresApprovalCount +
                                " · 已忽略 " + applied.ignoredCount +
                                " · 暂不支持 " + applied.unsupportedCount +
                                " · 失败 " + applied.failedCount,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        applied.appliedTitles.forEach { title ->
                            Text("- " + title, style = MaterialTheme.typography.bodySmall)
                        }
                        if (verification.hasSources) {
                            Spacer(Modifier.height(8.dp))
                            Text("逐源验证", style = MaterialTheme.typography.titleSmall)
                            Text(
                                SourceVerificationCopy.DIALOG_HINT,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            verification.sources.forEach { source ->
                                VerificationRow(
                                    source = source,
                                    line = verification.lineFor(source.serverId),
                                    state = verification.stateOf(source.serverId),
                                    busy = verification.isBusy(source.serverId),
                                    onVerify = onVerifyRequest,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            SourceSyncImportCopy.DISCLAIMER,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            verification.dialogSource?.let { source ->
                VerifyCredentialDialog(
                    source = source,
                    busy = verification.isBusy(source.serverId),
                    onConfirm = onVerifyConfirm,
                    onDismiss = onVerifyDismiss,
                )
            }
        }
    }
}

@Composable
private fun BucketSection(
    bucket: SourceSyncImportBucketView,
    interactive: Boolean,
    onDecide: (String, SourceSyncEntryDecision) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            SourceSyncImportCopy.titleOf(bucket.bucket) +
                "（" + bucket.entries.size + "）",
            style = MaterialTheme.typography.titleSmall,
        )
        SourceSyncImportCopy.noteOf(bucket.bucket)?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        bucket.entries.forEach { entry ->
            EntryCard(entry = entry, interactive = interactive, onDecide = onDecide)
        }
    }
}

@Composable
private fun EntryCard(
    entry: SourceSyncEntry,
    interactive: Boolean,
    onDecide: (String, SourceSyncEntryDecision) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SourceSyncImportTags.entry(entry.key)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
            entry.detail.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                entry.stateText,
                style = MaterialTheme.typography.bodySmall,
                color = if (!entry.allowable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag(SourceSyncImportTags.ENTRY_STATE),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onDecide(entry.key, SourceSyncEntryDecision.ALLOW) },
                    enabled = interactive && entry.allowable,
                    modifier = Modifier.testTag(SourceSyncImportTags.ENTRY_ALLOW),
                ) {
                    Text("允许")
                }
                OutlinedButton(
                    onClick = { onDecide(entry.key, SourceSyncEntryDecision.SKIP) },
                    enabled = interactive && entry.allowable,
                    modifier = Modifier.testTag(SourceSyncImportTags.ENTRY_SKIP),
                ) {
                    Text("跳过")
                }
            }
        }
    }
}

/**
 * 结果页里的一个来源：标题 + 三态状态行 + 「补录并验证」按钮。
 *
 * 状态行永远可见（未验证时显示"需补录凭据"），否则用户看不出"导入成功"与"已经登录"的区别。
 */
@Composable
private fun VerificationRow(
    source: SourceVerificationSource,
    line: String,
    state: SourceVerificationState,
    busy: Boolean,
    onVerify: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SourceSyncImportTags.verifyRow(source.serverId)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(source.title, style = MaterialTheme.typography.bodyMedium)
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = when (state) {
                SourceVerificationState.VERIFIED -> MaterialTheme.colorScheme.primary
                SourceVerificationState.FAILED -> MaterialTheme.colorScheme.error
                SourceVerificationState.NEEDS_CREDENTIALS ->
                    MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag(SourceSyncImportTags.VERIFY_STATE),
        )
        Button(
            onClick = { onVerify(source.serverId) },
            enabled = !busy,
            modifier = Modifier.testTag(SourceSyncImportTags.VERIFY),
        ) {
            Text(SourceVerificationCopy.VERIFY_ACTION)
        }
    }
}

/**
 * 补录凭据对话框。
 *
 * 密码框用 PasswordVisualTransformation（语义上带 Password 标记，UI 测试据此断言"输入被遮蔽"），
 * 密码只存在于本 composable 的局部状态里，随对话框关闭一起丢弃 —— 落盘只发生在认证通过之后。
 */
@Composable
private fun VerifyCredentialDialog(
    source: SourceVerificationSource,
    busy: Boolean,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var secret by remember(source.serverId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SourceSyncImportTags.VERIFY_DIALOG),
        title = { Text(SourceVerificationCopy.DIALOG_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    SourceVerificationCopy.DIALOG_HINT,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(SourceVerificationCopy.INPUT_LABEL) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SourceSyncImportTags.VERIFY_INPUT),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(source.serverId, secret) },
                enabled = !busy && secret.isNotBlank(),
                modifier = Modifier.testTag(SourceSyncImportTags.VERIFY_CONFIRM),
            ) {
                Text(SourceVerificationCopy.CONFIRM)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(SourceSyncImportTags.VERIFY_CANCEL),
            ) {
                Text(SourceVerificationCopy.CANCEL)
            }
        },
    )
}

/**
 * W6-27：「从 Windows 接收」区块。
 *
 * 表单只是三个输入框 + 一个按钮，**判定全在 SourceSyncReceiveController**：这里不算地址是否回环
 * （那是传输客户端的 not-loopback），也不猜失败原因（码与诊断原样显示）。端口没填好时按钮就是
 * 点不动的，并直接说明为什么——让用户对着禁用按钮猜原因是最糟的体验。
 */
@Composable
private fun ReceiveSection(
    state: SourceSyncReceiveState,
    onReceive: (SourceSyncReceiveForm) -> Unit,
) {
    var form by remember { mutableStateOf(SourceSyncReceiveForm()) }
    val receiving = state == SourceSyncReceiveState.Receiving
    val resultLine = SourceSyncReceiveCopy.lineOf(state)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SourceSyncImportTags.RECEIVE),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(SourceSyncReceiveCopy.TITLE, style = MaterialTheme.typography.titleSmall)
            Text(
                SourceSyncReceiveCopy.HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = form.host,
                onValueChange = { form = form.copy(host = it) },
                label = { Text(SourceSyncReceiveCopy.HOST_LABEL) },
                singleLine = true,
                enabled = !receiving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.RECEIVE_HOST),
            )
            OutlinedTextField(
                value = form.port,
                onValueChange = { form = form.copy(port = it) },
                label = { Text(SourceSyncReceiveCopy.PORT_LABEL) },
                singleLine = true,
                enabled = !receiving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.RECEIVE_PORT),
            )
            OutlinedTextField(
                value = form.challenge,
                onValueChange = { form = form.copy(challenge = it) },
                label = { Text(SourceSyncReceiveCopy.CHALLENGE_LABEL) },
                singleLine = true,
                enabled = !receiving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.RECEIVE_CHALLENGE),
            )
            Button(
                onClick = { onReceive(form) },
                enabled = form.canSubmit && !receiving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.RECEIVE_ACTION),
            ) {
                Text(SourceSyncReceiveCopy.ACTION)
            }
            if (receiving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(SourceSyncReceiveCopy.BUSY, style = MaterialTheme.typography.bodySmall)
                }
            } else if (!form.canSubmit) {
                Text(
                    SourceSyncReceiveCopy.PORT_REQUIRED,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (resultLine != null) {
                Text(
                    resultLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        is SourceSyncReceiveState.Received -> MaterialTheme.colorScheme.primary
                        is SourceSyncReceiveState.Failed, is SourceSyncReceiveState.Rejected ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag(SourceSyncImportTags.RECEIVE_STATE),
                )
            }
        }
    }
}

/** 读信封文本与文件名；读不出来返回 null（页面保持"未选文件"状态）。 */
private fun readEnvelope(context: Context, uri: Uri): Pair<String, String>? {
    val name = displayNameOf(context, uri)
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }.getOrNull()
    if (text.isNullOrBlank()) return null
    return text to (name ?: uri.lastPathSegment ?: "envelope.json")
}

private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}.getOrNull()
