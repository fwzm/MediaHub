package com.mediahub.feature.settings.paired

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.common.pairing.PairedDeviceRow
import com.mediahub.core.common.sync.ISourceSyncBaselineStore
import kotlinx.coroutines.launch

/** 配对设备页的测试标签（界面测试与文档共用）。 */
object PairedDeviceTags {

    const val SCREEN: String = "paired.screen"

    const val HINT: String = "paired.hint"

    const val LOADING: String = "paired.loading"

    const val UNAVAILABLE: String = "paired.unavailable"

    const val WARNING: String = "paired.warning"

    const val COUNT: String = "paired.count"

    const val CAPACITY: String = "paired.capacity"

    const val TOFU: String = "paired.tofu"

    const val EMPTY: String = "paired.empty"

    const val ROW: String = "paired.row"

    const val FORGET: String = "paired.forget"

    const val REFRESH: String = "paired.refresh"

    const val PROMPT: String = "paired.forget.prompt"

    const val PROMPT_CONFIRM: String = "paired.forget.confirm"

    const val PROMPT_CANCEL: String = "paired.forget.cancel"

    const val OUTCOME: String = "paired.outcome"

    const val OUTCOME_DISMISS: String = "paired.outcome.dismiss"
}

/**
 * 配对设备页入口（W6-70）：在本页内托管，返回即回设置列表。
 *
 * 存储出口在这里注入：生产实现是 filesDir/paired_devices/paired-devices.json，
 * 界面测试与预览可以换成内存实现——页面本身不含任何文件代码。
 */
@Composable
fun PairedDeviceRoute(
    onBack: () -> Unit,
    controller: PairedDeviceController? = null,
    baselineStore: ISourceSyncBaselineStore? = null,
) {
    val context = LocalContext.current
    val active = controller ?: remember(context, baselineStore) {
        PairedDeviceController(
            storeFactory = { PairedDeviceStorage.storeIn(context.applicationContext.filesDir) },
            baselineStore = baselineStore,
        )
    }
    val state by active.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // 进页就读一次本机登记：只读一个小文件，不拨号，所以不需要用户先点任何按钮。
    LaunchedEffect(active) {
        active.refresh()
    }
    PairedDeviceScreen(
        state = state,
        onRefresh = { scope.launch { active.refresh() } },
        onAskForget = { deviceId -> active.askForget(deviceId) },
        onConfirmForget = { scope.launch { active.confirmForget() } },
        onCancelForget = { active.cancelForget() },
        onDismissOutcome = { active.dismissOutcome() },
        onBack = onBack,
    )
}

/**
 * 配对设备页（无状态）：只读清单 + 忘记一台（两段确认）。
 *
 * 与 Windows 侧同义：这一页**不登记新设备**——登记要真的完成一次配对握手，属后续里程碑；
 * 这里只把本机已经记住的登记展示出来，并允许解绑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairedDeviceScreen(
    state: PairedDeviceUiState,
    onRefresh: () -> Unit,
    onAskForget: (String) -> Boolean,
    onConfirmForget: () -> Unit,
    onCancelForget: () -> Unit,
    onDismissOutcome: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag(PairedDeviceTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(PairedDeviceCopy.TITLE) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                PairedDeviceCopy.HINT,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(PairedDeviceTags.HINT),
            )
            when (val list = state.list) {
                PairedDeviceListState.Loading -> Text(
                    PairedDeviceCopy.LOADING,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(PairedDeviceTags.LOADING),
                )

                is PairedDeviceListState.Unavailable -> {
                    Text(
                        PairedDeviceCopy.unavailableText(list),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(PairedDeviceTags.UNAVAILABLE),
                    )
                    if (list.reason == PairedDeviceController.STORAGE_UNAVAILABLE) {
                        Text(
                            PairedDeviceCopy.STORAGE_UNAVAILABLE_HINT,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                is PairedDeviceListState.Ready -> {
                    val loadError = list.loadError
                    if (loadError != null) {
                        Text(
                            PairedDeviceCopy.warningOf(loadError),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag(PairedDeviceTags.WARNING),
                        )
                    }
                    Text(
                        list.view.countLabel,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag(PairedDeviceTags.COUNT),
                    )
                    if (list.view.capacityLabel.isNotEmpty()) {
                        Text(
                            list.view.capacityLabel,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag(PairedDeviceTags.CAPACITY),
                        )
                    }
                    Text(
                        list.view.tofuLabel,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(PairedDeviceTags.TOFU),
                    )
                    if (list.view.isEmpty) {
                        Text(
                            list.view.emptyLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(PairedDeviceTags.EMPTY),
                        )
                    } else {
                        for (index in list.view.rows.indices) {
                            val row = list.view.rows[index]
                            PairedDeviceRowCard(
                                row = row,
                                index = index,
                                busy = state.forgetting,
                                onForget = { onAskForget(row.deviceId) },
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.forgetting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PairedDeviceTags.REFRESH),
            ) {
                Text(PairedDeviceCopy.REFRESH)
            }
            val outcome = state.outcome
            if (outcome != null) {
                Text(
                    PairedDeviceCopy.outcomeText(outcome),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(PairedDeviceTags.OUTCOME),
                )
                TextButton(
                    onClick = onDismissOutcome,
                    modifier = Modifier.testTag(PairedDeviceTags.OUTCOME_DISMISS),
                ) {
                    Text(PairedDeviceCopy.DISMISS)
                }
            }
        }
        val prompt = state.prompt
        if (prompt != null) {
            AlertDialog(
                onDismissRequest = onCancelForget,
                modifier = Modifier.testTag(PairedDeviceTags.PROMPT),
                title = { Text(prompt.title) },
                text = { Text(prompt.text) },
                confirmButton = {
                    TextButton(
                        onClick = onConfirmForget,
                        enabled = !state.forgetting,
                        modifier = Modifier.testTag(PairedDeviceTags.PROMPT_CONFIRM),
                    ) {
                        Text(prompt.confirmLabel)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onCancelForget,
                        modifier = Modifier.testTag(PairedDeviceTags.PROMPT_CANCEL),
                    ) {
                        Text(prompt.cancelLabel)
                    }
                },
            )
        }
    }
}

/** 一行登记：摘要一行、细节一行，后面挂「忘记 名字」。 */
@Composable
private fun PairedDeviceRowCard(
    row: PairedDeviceRow,
    index: Int,
    busy: Boolean,
    onForget: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PairedDeviceTags.ROW + "." + index),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(row.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(row.detailLine, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onForget,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PairedDeviceTags.FORGET + "." + index),
            ) {
                Text(row.forgetLabel)
            }
        }
    }
}
