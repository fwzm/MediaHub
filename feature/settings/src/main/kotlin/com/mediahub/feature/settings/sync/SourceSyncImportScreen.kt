package com.mediahub.feature.settings.sync

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    fun entry(key: String): String = "sync.entry:" + key
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSyncImportRoute(
    onBack: () -> Unit,
    viewModel: SourceSyncImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    )
}

/**
 * 无状态导入页：整页只由 [state] 与五个回调驱动。
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
                        Spacer(Modifier.height(8.dp))
                        Text(
                            SourceSyncImportCopy.DISCLAIMER,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
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
