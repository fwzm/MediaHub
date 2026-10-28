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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
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

    /** W6-82：本轮的「计划」一行（预览里怎么分桶）。 */
    const val APPLY_PLAN = "sync.apply.plan"

    /** W6-82：本轮的「结果」一行（写入 / 删除 / 跳过 / 扣住 / 拒写）。 */
    const val APPLY_VERDICT = "sync.apply.verdict"

    /** W6-83：逐条被永久扣住的条目（标题 + 每条原因；0 条时整块不出现）。 */
    const val APPLY_WITHHELD = "sync.apply.withheld"

    /** W6-84：逐条「复制身份」按钮（载荷是身份原文，不是标题；同页多条时必须能限定到某一条）。 */
    fun withheldCopy(syncSourceId: String): String = "sync.apply.copy:" + syncSourceId

    /** W6-85：预览页条目自己的复制按钮（与结果页同页不同段，tag 不能复用）。 */
    fun entryCopy(key: String): String = "sync.entry.copy:" + key

    /** W6-85：预览页不可允许条目的「下一步」一行（每个条目各一条）。 */
    const val ENTRY_NEXT_STEP = "sync.entry.nextStep"

    /** W6-82：逐条没写成的标题（每条原因各占一行，紧跟在下面）。 */
    const val APPLY_REFUSED = "sync.apply.refused"
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

    // ---- W6-72：导入结果里的端点与基线两条事实 ----

    /** 结果卡片里「未同步的次要端点 N 个」那一行。 */
    const val ENDPOINT_SUMMARY = "sync.apply.endpoints"

    /** 结果卡片里「已记入对端基线」/「基线拒绝 N 条」那一行。 */
    const val BASELINE_STATE = "sync.apply.baseline"

    // ---- W6-27：从 Windows 接收 ----

    /** 接收区块本体（地址 / 端口 / 挑战 / 接收按钮）。 */
    const val RECEIVE = "sync.receive"

    const val RECEIVE_HOST = "sync.receive.host"

    /** W6-52b：允许连一个非回环单播地址的开关。 */
    const val RECEIVE_ALLOW_HOST = "sync.receive.allow-host"

    const val RECEIVE_PORT = "sync.receive.port"

    const val RECEIVE_CHALLENGE = "sync.receive.challenge"

    const val RECEIVE_ACTION = "sync.receive.action"

    /** 接收结果行（已收下 / 接收失败：码 / 信封被拒：码）。 */
    const val RECEIVE_STATE = "sync.receive.state"

    /** W6-58：取消正在飞的那一次接收（只在接收中可点）。 */
    const val RECEIVE_CANCEL = "sync.receive.cancel"

    // ---- W6-36：接收（监听） ----

    /** 监听区块本体（端口 / TLS 开关 / 开始 / 停止 / 等一次发送）。 */
    const val LISTEN = "sync.listen"

    const val LISTEN_PORT = "sync.listen.port"

    /** W6-52：绑定地址输入框。 */
    const val LISTEN_HOST = "sync.listen.host"

    /** W6-52：允许绑非回环单播地址的开关。 */
    const val LISTEN_ALLOW_HOST = "sync.listen.allow-host"

    const val LISTEN_TLS = "sync.listen.tls"

    /** W6-53：要求对端带口令（HMAC 认证）的开关。 */
    const val LISTEN_AUTH = "sync.listen.auth"

    /** W6-53：对端口令输入框（按密码显示，只留在内存）。 */
    const val LISTEN_SECRET = "sync.listen.secret"

    /** W6-54：等待预算（毫秒）输入框。 */
    const val LISTEN_BUDGET = "sync.listen.budget"

    /** 收下后回一个裸 0x04（W6-40）：对端才能区分「接受」与「收完就断」。 */
    const val LISTEN_RECEIPT = "sync.listen.receipt"

    const val LISTEN_START = "sync.listen.start"

    const val LISTEN_STOP = "sync.listen.stop"

    const val LISTEN_RECEIVE = "sync.listen.receive"

    /** W6-61：取消这一次等待（只在接收中可点；取消回「正在监听」，监听不关，不留码）。 */
    const val LISTEN_CANCEL = "sync.listen.cancel"

    /** 监听状态行（正在监听 / 接收中 / 已收下 / 失败：码 / 信封被拒：码）。 */
    const val LISTEN_STATUS = "sync.listen.status"

    /** 端口行：TLS 时带证书指纹，用户要抄给 Windows 的就是这行。 */
    const val LISTEN_PORT_LINE = "sync.listen.port-line"

    /** TLS 可用性那句实话（不可用时必须看得见，且开关点不动）。 */
    const val LISTEN_INFO = "sync.listen.info"

    // ---- W6-42：发送（Android → Windows） ----

    /** 发送区块本体（地址 / 端口 / 挑战 / TLS 与指纹 / 认证与口令 / 回执 / 发送按钮）。 */
    const val SEND = "sync.send"

    const val SEND_HOST = "sync.send.host"

    /** W6-52b：允许发给一个非回环单播地址的开关。 */
    const val SEND_ALLOW_HOST = "sync.send.allow-host"

    const val SEND_PORT = "sync.send.port"

    const val SEND_CHALLENGE = "sync.send.challenge"

    const val SEND_TLS = "sync.send.tls"

    /** 期望指纹输入框（TLS 关着时禁用，但仍然存在）。 */
    const val SEND_PIN = "sync.send.pin"

    const val SEND_AUTH = "sync.send.auth"

    /** 口令输入框（认证关着时禁用；文本被 PasswordVisualTransformation 遮蔽）。 */
    const val SEND_SECRET = "sync.send.secret"

    const val SEND_RECEIPT = "sync.send.receipt"

    /** 等对端关闭连接（W6-46 投递确认）：与回执同时开时回执优先，所以勾了回执它就禁用。 */
    const val SEND_DELIVERY = "sync.send.delivery"

    const val SEND_ACTION = "sync.send.action"

    /** W6-49：取消按钮（只在发送中可点；取消回 Idle，不产生失败码）。 */
    const val SEND_CANCEL = "sync.send.cancel"

    /** 发送结果行（已发送 / 发送中 / 发送失败：码）。 */
    const val SEND_STATE = "sync.send.state"

    /** W6-63：加密归档的口令卡（只在选到 .mharchive 时出现）。 */
    const val PASSPHRASE_CARD = "sync.import.passphrase.card"

    /** W6-63：加密归档的口令输入框（只在选到 .mharchive 时出现）。 */
    const val PASSPHRASE = "sync.import.passphrase"

    /** W6-63：口令卡上的「解密」按钮。 */
    const val UNLOCK = "sync.import.unlock"

    /** W6-63：口令卡的错误行（口令不对 / 归档被改坏时带 ArchiveError 原码）。 */
    const val PASSPHRASE_ERROR = "sync.import.passphrase.error"

    /** W6-64：导出（另存为文件）卡片。 */
    const val EXPORT_CARD = "sync.export.card"

    /** W6-64：展开导出卡片的按钮。 */
    const val EXPORT_ACTION = "sync.export.action"

    /** W6-64：导出卡的口令输入框（留空 = 明文信封，非空 = 加密归档）。 */
    const val EXPORT_PASSPHRASE = "sync.export.passphrase"

    /** W6-64：生成产物并拉起系统保存器的按钮。 */
    const val EXPORT_SUBMIT = "sync.export.submit"

    /** W6-64：导出结果行（已导出 / 失败：码）。 */
    const val EXPORT_STATE = "sync.export.state"

    /** W6-64：收起导出卡片（丢弃暂存字节）。 */
    const val EXPORT_DISMISS = "sync.export.dismiss"
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
    val listenState by viewModel.listenState.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    val archivePrompt by viewModel.archivePrompt.collectAsStateWithLifecycle()
    val archiveExportState by viewModel.archiveExportState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // W6-84：扣住/拒写条目旁边的「复制身份」把对端给的身份原文放进剪贴板，
    // 用户要拿它去对端机器上找这条来源 —— 界面上是纯文本，选不中、抄不走。
    val clipboard = LocalClipboardManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val loaded = readPickedDocument(context, uri)
            if (loaded != null) {
                viewModel.loadPickedDocument(loaded.first, loaded.second)
            }
        }
    }
    // W6-64：导出产物一旦就绪就拉起系统保存位置选择器（与备份页同一范式：
    // 选好位置才写盘，用户点取消则丢弃字节、一个字节都不落下）。
    val exportSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) viewModel.saveArchiveExport(uri.toString()) else viewModel.cancelArchiveExportSave()
    }
    LaunchedEffect(archiveExportState) {
        val ready = archiveExportState as? SourceSyncArchiveExportState.Ready ?: return@LaunchedEffect
        exportSaver.launch(ready.fileName)
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
        onReceiveCancel = viewModel::cancelReceive,
        listenState = listenState,
        listenTls = viewModel.listenTls,
        onListenStart = viewModel::startListening,
        onListenStop = viewModel::stopListening,
        onListenReceive = viewModel::awaitListenOnce,
        onListenCancel = viewModel::cancelListening,
        sendState = sendState,
        onSend = viewModel::send,
        onSendCancel = viewModel::cancelSend,
        archivePrompt = archivePrompt,
        onUnlockArchive = viewModel::unlockArchive,
        archiveExportState = archiveExportState,
        onExportRequest = viewModel::requestArchiveExport,
        onExportPrepare = viewModel::prepareArchiveExport,
        onExportDismiss = viewModel::dismissArchiveExport,
        onCopyIdentity = { identity -> clipboard.setText(AnnotatedString(identity)) },
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
    /** W6-58：取消正在飞的那一次接收；默认空实现（既有调用点与 UI 测试无需改动）。 */
    onReceiveCancel: () -> Unit = {},
    /** W6-36：手机当接收端（监听）区块的状态与可用性；默认 Idle + TLS 不可用。 */
    listenState: SourceSyncListenState = SourceSyncListenState.Idle,
    listenTls: SourceSyncListenTlsAvailability = SourceSyncListenTlsAvailability(
        available = false,
        note = SourceSyncListenCopy.TLS_UNAVAILABLE,
    ),
    onListenStart: (SourceSyncListenForm) -> Unit = {},
    onListenStop: () -> Unit = {},
    onListenReceive: () -> Unit = {},
    /** W6-61：取消正在飞的那一次等待；默认空实现（既有调用点与 UI 测试无需改动）。 */
    onListenCancel: () -> Unit = {},
    /** W6-42：发送（Android → Windows）区块的状态；默认 Idle（既有调用点与 UI 测试无需改动）。 */
    sendState: SourceSyncSendState = SourceSyncSendState.Idle,
    onSend: (SourceSyncSendForm) -> Unit = {},
    /** W6-49：取消正在飞的那一次发送；默认空实现（既有调用点与 UI 测试无需改动）。 */
    onSendCancel: () -> Unit = {},
    /** W6-63：加密归档的口令卡状态；默认不可见（既有调用点与 UI 测试无需改动）。 */
    archivePrompt: SourceSyncArchivePrompt = SourceSyncArchivePrompt(),
    /** W6-63：提交口令解密；默认空实现（既有调用点与 UI 测试无需改动）。 */
    onUnlockArchive: (String) -> Unit = {},
    /** W6-64：导出（另存为文件）卡片的状态；默认 Idle（既有调用点与 UI 测试无需改动）。 */
    archiveExportState: SourceSyncArchiveExportState = SourceSyncArchiveExportState.Idle,
    /** W6-64：展开导出卡片；默认空实现。 */
    onExportRequest: () -> Unit = {},
    /** W6-64：提交口令生成产物（留空 = 明文）；默认空实现。 */
    onExportPrepare: (String) -> Unit = {},
    /** W6-64：收起导出卡片并丢弃暂存字节；默认空实现。 */
    /** W6-84：把一条条目的身份原文交给剪贴板（默认空实现，既有调用点与 UI 测试无需改动）。 */
    onCopyIdentity: (String) -> Unit = {},
    onExportDismiss: () -> Unit = {},
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
                SourceSyncImportCopy.PICK_HINT,
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

            if (archivePrompt.visible) {
                // 口令只活在这个分支的局部状态里：卡片一消失（解开、重选、离开页面）它就被丢弃，
                // 不写 StateFlow、不落盘、不进日志。PBKDF2 在 ViewModel 里跑，这里只管输入。
                var passphrase by remember { mutableStateOf("") }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SourceSyncImportTags.PASSPHRASE_CARD),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            SourceSyncArchiveCopy.HINT,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text(SourceSyncArchiveCopy.LABEL) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SourceSyncImportTags.PASSPHRASE),
                        )
                        Button(
                            onClick = { onUnlockArchive(passphrase) },
                            enabled = passphrase.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SourceSyncImportTags.UNLOCK),
                        ) {
                            Text(SourceSyncArchiveCopy.ACTION)
                        }
                        archivePrompt.error?.let { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag(SourceSyncImportTags.PASSPHRASE_ERROR),
                            )
                        }
                    }
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
                            onCopyIdentity = onCopyIdentity,
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
                        // W6-82：这一行是预览里的桶（计划），下一行才是这一轮真正做了什么（结果）。
                        Text(
                            SourceSyncImportCopy.planLine(applied),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(SourceSyncImportTags.APPLY_PLAN),
                        )
                        Text(
                            SourceSyncImportCopy.resultLine(applied),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag(SourceSyncImportTags.APPLY_VERDICT),
                        )
                        // W6-83：扣住的条目逐条点名 + 各自原因（W6-81 只给了一行去重原因码）。
                        SourceSyncImportCopy.withheldHeader(applied)?.let { header ->
                            Text(
                                header,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag(SourceSyncImportTags.APPLY_WITHHELD),
                            )
                            applied.withheldEntries.forEach { entry ->
                                // W6-84：原因句之后补一句「下一步」，右侧把身份抄进剪贴板。
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        SourceSyncImportCopy.withheldLine(entry),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    SourceSyncImportCopy.nextStepOf(entry)?.let { nextStep ->
                                        Text(
                                            nextStep,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IdentityCopyButton(
                                        identity = SourceSyncImportCopy.copyPayloadOf(entry),
                                        tag = SourceSyncImportTags.withheldCopy(entry.syncSourceId),
                                        onCopyIdentity = onCopyIdentity,
                                    )
                                }
                            }
                        }

                        applied.appliedTitles.forEach { title ->
                            Text("- " + title, style = MaterialTheme.typography.bodySmall)
                        }
                        // W6-82：没写成的每一条都点名 + 给一句原因；只有计数时用户无从下手。
                        SourceSyncImportCopy.refusedHeader(applied)?.let { header ->
                            Text(
                                header,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag(SourceSyncImportTags.APPLY_REFUSED),
                            )
                            applied.refusedEntries.forEach { entry ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        SourceSyncImportCopy.refusalLine(entry),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    IdentityCopyButton(
                                        identity = SourceSyncImportCopy.copyPayloadOf(entry),
                                        tag = SourceSyncImportTags.withheldCopy(entry.syncSourceId),
                                        onCopyIdentity = onCopyIdentity,
                                    )
                                }
                            }
                        }
                        // W6-72：多端点与基线两条事实，只有真的发生时出现（0 条时不占版面）。
                        if (applied.notImportedEndpointCount > 0) {
                            Text(
                                SourceSyncImportCopy.notImportedEndpoints(applied),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag(SourceSyncImportTags.ENDPOINT_SUMMARY),
                            )
                        }
                        if (applied.baselineSaved) {
                            Text(
                                SourceSyncImportCopy.BASELINE_SAVED,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag(SourceSyncImportTags.BASELINE_STATE),
                            )
                        } else if (applied.baselineRejectedCount > 0) {
                            Text(
                                SourceSyncImportCopy.baselineRejected(applied.baselineRejectedCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag(SourceSyncImportTags.BASELINE_STATE),
                            )
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

            // W6-41：两个接收区块刻意放在整页末尾 —— 首屏保留 W6-27 之前的
            // 「选择文件 / 预览」主流程构成，instrumentation 依赖首屏可见性
            // （SourceSyncImportScreenTest 的 pick/preview/blocked 断言不做滚动），
            // 而接收区块只是入口捷径，往下滚一格就能看到。
            ReceiveSection(
                state = receiveState,
                onReceive = onReceive,
                onCancel = onReceiveCancel,
            )
            ListenSection(
                state = listenState,
                tls = listenTls,
                onStart = onListenStart,
                onStop = onListenStop,
                onReceive = onListenReceive,
                onCancel = onListenCancel,
            )
            // W6-42：发送区块同样放页尾，理由与上面两块一致（首屏不动）。
            SendSection(state = sendState, onSend = onSend, onCancel = onSendCancel)
            // W6-64：导出（另存为文件）同样放页尾，首屏保持「选择文件 / 预览」主流程。
            ArchiveExportSection(
                state = archiveExportState,
                onRequest = onExportRequest,
                onPrepare = onExportPrepare,
                onDismiss = onExportDismiss,
            )
        }
    }
}

@Composable
private fun BucketSection(
    bucket: SourceSyncImportBucketView,
    interactive: Boolean,
    onDecide: (String, SourceSyncEntryDecision) -> Unit,
    onCopyIdentity: (String) -> Unit,
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
            EntryCard(
                entry = entry,
                interactive = interactive,
                onDecide = onDecide,
                onCopyIdentity = onCopyIdentity,
            )
        }
    }
}

/**
 * W6-84：一条「复制身份」按钮。
 *
 * 载荷只由 Copy 层决定（[SourceSyncImportCopy.copyPayloadOf]），这里不碰身份拼装 ——
 * 否则「复制了标题」这种错要让模拟器测试才发现。
 */
@Composable
private fun IdentityCopyButton(
    identity: String,
    tag: String,
    onCopyIdentity: (String) -> Unit,
) {
    TextButton(
        onClick = { onCopyIdentity(identity) },
        modifier = Modifier.testTag(tag),
    ) {
        Text(SourceSyncImportCopy.COPY_IDENTITY, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EntryCard(
    entry: SourceSyncEntry,
    interactive: Boolean,
    onDecide: (String, SourceSyncEntryDecision) -> Unit,
    onCopyIdentity: (String) -> Unit,
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
            // W6-85：预览阶段就把「为什么不行 + 下一步」摆出来。之前只有结果页有出口，
            // 用户在预演里看见一条永不许可的条目，只能靠猜。
            if (!entry.allowable) {
                SourceSyncImportCopy.nextStepOf(entry)?.let { nextStep ->
                    Text(
                        nextStep,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(SourceSyncImportTags.ENTRY_NEXT_STEP),
                    )
                }
                SourceSyncImportCopy.copyPayloadOf(entry)?.let { identity ->
                    IdentityCopyButton(
                        identity = identity,
                        tag = SourceSyncImportTags.entryCopy(entry.key),
                        onCopyIdentity = onCopyIdentity,
                    )
                }
            }
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
    onCancel: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.allowExplicitHost,
                    onCheckedChange = { checked -> form = form.copy(allowExplicitHost = checked) },
                    enabled = !receiving,
                    modifier = Modifier.testTag(SourceSyncImportTags.RECEIVE_ALLOW_HOST),
                )
                Text(
                    SourceSyncReceiveCopy.ALLOW_HOST_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                SourceSyncReceiveCopy.ALLOW_HOST_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // W6-58：取消只在接收中可点。沿用本页「禁用而不是隐藏」的纪律（tag 与布局稳定），
            // 取消回 Idle、不显示失败码——用户按的取消不是错误。
            OutlinedButton(
                onClick = onCancel,
                enabled = receiving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.RECEIVE_CANCEL),
            ) {
                Text(SourceSyncReceiveCopy.CANCEL)
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

/**
 * W6-36：「接收（监听）」区块——手机当服务端，等 Windows 端点用 transfer send 发过来。
 *
 * 与上一个区块的区别写在标题里：上一个（从 Windows 接收）是手机主动拨号；这一块是手机开端口等对端。
 * 判定同样全在 SourceSyncListenController：这里只画输入框、两个按钮与两行文案，
 * 不猜原因、不把码折成布尔。TLS 不可用时开关直接 disabled 并显示那句实话——
 * 让用户面对一个点了必然失败的开关是最糟的体验。
 */
@Composable
private fun ListenSection(
    state: SourceSyncListenState,
    tls: SourceSyncListenTlsAvailability,
    onStart: (SourceSyncListenForm) -> Unit,
    onStop: () -> Unit,
    onReceive: () -> Unit,
    onCancel: () -> Unit,
) {
    var form by remember { mutableStateOf(SourceSyncListenForm()) }
    val listening = state is SourceSyncListenState.Listening ||
        state is SourceSyncListenState.Receiving ||
        state is SourceSyncListenState.Received ||
        state is SourceSyncListenState.Rejected ||
        state is SourceSyncListenState.Failed && state.port != null
    val receiving = state is SourceSyncListenState.Receiving
    val statusLine = SourceSyncListenCopy.lineOf(state)
    val portLine = SourceSyncListenCopy.portLineOf(state)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SourceSyncImportTags.LISTEN),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(SourceSyncListenCopy.TITLE, style = MaterialTheme.typography.titleSmall)
            Text(
                SourceSyncListenCopy.HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = form.host,
                onValueChange = { form = form.copy(host = it) },
                label = { Text(SourceSyncListenCopy.HOST_LABEL) },
                singleLine = true,
                enabled = !listening && !receiving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.LISTEN_HOST),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.allowExplicitHost,
                    onCheckedChange = { checked -> form = form.copy(allowExplicitHost = checked) },
                    enabled = !listening && !receiving,
                    modifier = Modifier.testTag(SourceSyncImportTags.LISTEN_ALLOW_HOST),
                )
                Text(
                    SourceSyncListenCopy.ALLOW_HOST_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                SourceSyncListenCopy.ALLOW_HOST_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = form.port,
                onValueChange = { form = form.copy(port = it) },
                label = { Text(SourceSyncListenCopy.PORT_LABEL) },
                singleLine = true,
                enabled = !listening && !receiving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.LISTEN_PORT),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.tls,
                    onCheckedChange = { checked -> form = form.copy(tls = checked) },
                    enabled = tls.available && !listening && !receiving,
                    modifier = Modifier.testTag(SourceSyncImportTags.LISTEN_TLS),
                )
                Text(
                    SourceSyncListenCopy.TLS_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                tls.note,
                style = MaterialTheme.typography.bodySmall,
                color = if (tls.available) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.testTag(SourceSyncImportTags.LISTEN_INFO),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.auth,
                    onCheckedChange = { checked -> form = form.copy(auth = checked) },
                    enabled = !listening && !receiving,
                    modifier = Modifier.testTag(SourceSyncImportTags.LISTEN_AUTH),
                )
                Text(
                    SourceSyncListenCopy.AUTH_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = form.secret,
                onValueChange = { form = form.copy(secret = it) },
                label = { Text(SourceSyncListenCopy.SECRET_LABEL) },
                singleLine = true,
                enabled = !listening && !receiving && form.auth,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.LISTEN_SECRET),
            )
            Text(
                SourceSyncListenCopy.AUTH_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = form.budget,
                onValueChange = { form = form.copy(budget = it) },
                label = { Text(SourceSyncListenCopy.BUDGET_LABEL) },
                singleLine = true,
                enabled = !listening && !receiving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.LISTEN_BUDGET),
            )
            Text(
                SourceSyncListenCopy.BUDGET_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.receipt,
                    onCheckedChange = { checked -> form = form.copy(receipt = checked) },
                    enabled = !listening && !receiving,
                    modifier = Modifier.testTag(SourceSyncImportTags.LISTEN_RECEIPT),
                )
                Text(
                    SourceSyncListenCopy.RECEIPT_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                SourceSyncListenCopy.RECEIPT_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onStart(form) },
                    enabled = form.canSubmit && !listening && !receiving,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SourceSyncImportTags.LISTEN_START),
                ) {
                    Text(SourceSyncListenCopy.START)
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = listening,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SourceSyncImportTags.LISTEN_STOP),
                ) {
                    Text(SourceSyncListenCopy.STOP)
                }
            }
            Button(
                onClick = onReceive,
                enabled = (state is SourceSyncListenState.Listening ||
                    state is SourceSyncListenState.Received ||
                    state is SourceSyncListenState.Rejected) && !receiving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.LISTEN_RECEIVE),
            ) {
                Text(SourceSyncListenCopy.RECEIVE_ONE)
            }
            OutlinedButton(
                onClick = onCancel,
                enabled = receiving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.LISTEN_CANCEL),
            ) {
                Text(SourceSyncListenCopy.CANCEL_RECEIVE)
            }
            if (receiving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(SourceSyncListenCopy.BUSY, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (portLine != null) {
                Text(
                    portLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(SourceSyncImportTags.LISTEN_PORT_LINE),
                )
            }
            if (statusLine != null) {
                Text(
                    statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        is SourceSyncListenState.Received -> MaterialTheme.colorScheme.primary
                        is SourceSyncListenState.Failed, is SourceSyncListenState.Rejected ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag(SourceSyncImportTags.LISTEN_STATUS),
                )
            }
        }
    }
}

/**
 * W6-42：「发送（Android → Windows）」区块——手机当发送端，把本机信封推给 Windows 的 transfer serve。
 *
 * 与上面两块的区别写在标题里：那两块是 Windows 发过来（一块手机拨号、一块手机开端口等），
 * 这一块是手机主动推给 Windows。判定全在 [SourceSyncSendController]：这里只画输入框、三个开关
 * 与一行结果，不算地址是否回环、不猜失败原因（码与诊断原样显示）。
 *
 * 指纹与口令只在对应开关打开时才有意义，所以关着时**禁用而不是隐藏**：tag 与键盘状态稳定，
 * UI 测试不必因为开关状态去分支查找。没填好时按钮点不动，并直接说明缺哪一项——
 * 让用户对着禁用按钮猜原因是最糟的体验。
 */
@Composable
private fun SendSection(
    state: SourceSyncSendState,
    onSend: (SourceSyncSendForm) -> Unit,
    onCancel: () -> Unit,
) {
    var form by remember { mutableStateOf(SourceSyncSendForm()) }
    val sending = state == SourceSyncSendState.Sending
    val resultLine = SourceSyncSendCopy.lineOf(state)
    val missing = when {
        form.portNumber == null -> SourceSyncSendCopy.PORT_REQUIRED
        !form.isFingerprintAcceptable -> SourceSyncSendCopy.PIN_REQUIRED
        !form.isSecretAcceptable -> SourceSyncSendCopy.SECRET_REQUIRED
        else -> null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SourceSyncImportTags.SEND),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(SourceSyncSendCopy.TITLE, style = MaterialTheme.typography.titleSmall)
            Text(
                SourceSyncSendCopy.HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = form.host,
                onValueChange = { form = form.copy(host = it) },
                label = { Text(SourceSyncSendCopy.HOST_LABEL) },
                singleLine = true,
                enabled = !sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.SEND_HOST),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.allowExplicitHost,
                    onCheckedChange = { checked -> form = form.copy(allowExplicitHost = checked) },
                    enabled = !sending,
                    modifier = Modifier.testTag(SourceSyncImportTags.SEND_ALLOW_HOST),
                )
                Text(
                    SourceSyncSendCopy.ALLOW_HOST_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                SourceSyncSendCopy.ALLOW_HOST_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = form.port,
                onValueChange = { form = form.copy(port = it) },
                label = { Text(SourceSyncSendCopy.PORT_LABEL) },
                singleLine = true,
                enabled = !sending,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.SEND_PORT),
            )
            OutlinedTextField(
                value = form.challenge,
                onValueChange = { form = form.copy(challenge = it) },
                label = { Text(SourceSyncSendCopy.CHALLENGE_LABEL) },
                singleLine = true,
                enabled = !sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.SEND_CHALLENGE),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.tls,
                    onCheckedChange = { checked -> form = form.copy(tls = checked) },
                    enabled = !sending,
                    modifier = Modifier.testTag(SourceSyncImportTags.SEND_TLS),
                )
                Text(
                    SourceSyncSendCopy.TLS_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = form.fingerprint,
                onValueChange = { form = form.copy(fingerprint = it) },
                label = { Text(SourceSyncSendCopy.PIN_LABEL) },
                singleLine = true,
                enabled = !sending && form.tls,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.SEND_PIN),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.auth,
                    onCheckedChange = { checked -> form = form.copy(auth = checked) },
                    enabled = !sending,
                    modifier = Modifier.testTag(SourceSyncImportTags.SEND_AUTH),
                )
                Text(
                    SourceSyncSendCopy.AUTH_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = form.secret,
                onValueChange = { form = form.copy(secret = it) },
                label = { Text(SourceSyncSendCopy.SECRET_LABEL) },
                singleLine = true,
                enabled = !sending && form.auth,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.SEND_SECRET),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.receipt,
                    onCheckedChange = { checked -> form = form.copy(receipt = checked) },
                    enabled = !sending,
                    modifier = Modifier.testTag(SourceSyncImportTags.SEND_RECEIPT),
                )
                Text(
                    SourceSyncSendCopy.RECEIPT_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                SourceSyncSendCopy.RECEIPT_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.delivery,
                    // 回执优先：勾着等回执时投递确认不起作用，所以禁用而不是「勾了也没用」。
                    onCheckedChange = { checked -> form = form.copy(delivery = checked) },
                    enabled = !sending && !form.receipt,
                    modifier = Modifier.testTag(SourceSyncImportTags.SEND_DELIVERY),
                )
                Text(
                    SourceSyncSendCopy.DELIVERY_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                SourceSyncSendCopy.DELIVERY_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onSend(form) },
                enabled = form.canSubmit && !sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.SEND_ACTION),
            ) {
                Text(SourceSyncSendCopy.ACTION)
            }
            // W6-49：取消只在发送中可点。这里沿用本区块的「禁用而不是隐藏」纪律
            // （tag 与布局稳定，UI 测试不必为状态切换去分支查找）；取消回 Idle，不显示失败码。
            OutlinedButton(
                onClick = onCancel,
                enabled = sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceSyncImportTags.SEND_CANCEL),
            ) {
                Text(SourceSyncSendCopy.CANCEL)
            }
            if (sending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(SourceSyncSendCopy.BUSY, style = MaterialTheme.typography.bodySmall)
                }
            } else if (missing != null) {
                Text(
                    missing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (resultLine != null) {
                Text(
                    resultLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        is SourceSyncSendState.Sent -> MaterialTheme.colorScheme.primary
                        is SourceSyncSendState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag(SourceSyncImportTags.SEND_STATE),
                )
            }
        }
    }
}

/**
 * W6-64：导出（另存为文件）区块。
 *
 * 三段式：收起时只有一行「导出」按钮 → 展开后填口令 → 产物就绪后由 Route 的
 * LaunchedEffect 拉起系统保存器（本区块不直接碰 Activity Result）。
 * 口令留空导出明文信封，非空导出加密归档；两种产物共用同一份导出数据。
 */
@Composable
private fun ArchiveExportSection(
    state: SourceSyncArchiveExportState,
    onRequest: () -> Unit,
    onPrepare: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    val busy = state is SourceSyncArchiveExportState.Saving
    val resultLine = when (state) {
        is SourceSyncArchiveExportState.Saved -> SourceSyncArchiveExportCopy.savedLineOf(state)
        is SourceSyncArchiveExportState.Failed -> SourceSyncArchiveExportCopy.failedLineOf(state.code)
        else -> null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SourceSyncImportTags.EXPORT_CARD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(SourceSyncArchiveExportCopy.TITLE, style = MaterialTheme.typography.titleSmall)
            Text(
                SourceSyncArchiveExportCopy.HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state is SourceSyncArchiveExportState.Idle) {
                Button(
                    onClick = onRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SourceSyncImportTags.EXPORT_ACTION),
                ) {
                    Text(SourceSyncArchiveExportCopy.ACTION)
                }
            } else {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(SourceSyncArchiveExportCopy.PASSPHRASE_LABEL) },
                    singleLine = true,
                    enabled = !busy && state !is SourceSyncArchiveExportState.Saved,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SourceSyncImportTags.EXPORT_PASSPHRASE),
                )
                // 生成物就绪/保存中时按钮禁用，避免连点生成两份不同字节。
                Button(
                    onClick = { onPrepare(passphrase) },
                    enabled = !busy &&
                        state !is SourceSyncArchiveExportState.Ready &&
                        state !is SourceSyncArchiveExportState.Saved,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SourceSyncImportTags.EXPORT_SUBMIT),
                ) {
                    Text(SourceSyncArchiveExportCopy.ACTION)
                }
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SourceSyncImportTags.EXPORT_DISMISS),
                ) {
                    Text(SourceSyncArchiveExportCopy.CANCEL)
                }
            }
            if (state is SourceSyncArchiveExportState.Ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "等待选择保存位置…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("正在写入…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (resultLine != null) {
                Text(
                    resultLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is SourceSyncArchiveExportState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.testTag(SourceSyncImportTags.EXPORT_STATE),
                )
            }
        }
    }
}
/**
 * 读选中文件的原始字节与文件名；读不出来返回 null（页面保持"未选文件"状态）。
 *
 * W6-63 起必须按字节读：加密归档（.mharchive）是二进制，按文本解码会把它毁掉。
 * 明文信封继续由 ViewModel 用 UTF-8 宽松解码，行为与 W6-63 之前一致。
 */
private fun readPickedDocument(context: Context, uri: Uri): Pair<ByteArray, String>? {
    val name = displayNameOf(context, uri)
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
    }.getOrNull()
    if (bytes == null || bytes.isEmpty()) return null
    return bytes to (name ?: uri.lastPathSegment ?: "envelope.json")
}

private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}.getOrNull()