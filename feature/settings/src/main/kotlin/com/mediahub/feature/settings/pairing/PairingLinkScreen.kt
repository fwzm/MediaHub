package com.mediahub.feature.settings.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.common.pairing.PairingAddressEnumerator
import kotlinx.coroutines.launch

/**
 * 配对页的测试标签（界面测试与文档共用）。
 */
object PairingLinkTags {

    const val SCREEN: String = "pairing.screen"

    const val LINK: String = "pairing.link"

    const val ACTION: String = "pairing.action"

    const val FETCH: String = "pairing.fetch"

    const val STATE: String = "pairing.state"

    const val CLEAR: String = "pairing.clear"

    const val ADDRESSES: String = "pairing.addresses"

    const val ADDRESS_LINE: String = "pairing.addresses.line"

    const val ADDRESS_STATE: String = "pairing.addresses.state"

    const val ADDRESS_REFRESH: String = "pairing.addresses.refresh"
}

/**
 * 配对入口（W6-59 / W6-59b）：在设置页内托管，返回即回设置列表。
 *
 * 拉邀请是唯一会拨号的动作，所以出口在这里注入：生产实现是 TLS 1.3 + SPKI 指纹钉扎，
 * 界面测试与预览可以换成替身，页面本身不含任何网络代码。
 */
@Composable
fun PairingLinkRoute(
    onBack: () -> Unit,
    controller: PairingLinkController = remember {
        PairingLinkController(
            fetchPort = PairingTransportFetchPort(),
            addressEnumerator = PairingAddressEnumerator(),
        )
    },
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val addresses by controller.addresses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // 进页就读一次本机地址：读的是网卡属性，不发包，所以不需要用户先点任何按钮。
    LaunchedEffect(controller) {
        controller.refreshAddresses()
    }
    PairingLinkScreen(
        state = state,
        addressState = addresses,
        onVerify = { link -> controller.verify(link) },
        onFetch = { scope.launch { controller.fetchInvitation() } },
        onRefreshAddresses = { controller.refreshAddresses() },
        onClear = { controller.clear() },
        onBack = onBack,
    )
}

/**
 * 配对页（无状态）：贴链接 → 核对 → 拉邀请（TLS 1.3 + 指纹钉扎）。
 *
 * 两个动作分开摆：核对不发字节，拉邀请才拨号。页面上写明后者做了什么、失败给什么码，
 * 避免用户把「核对成功」当成「已经配好」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingLinkScreen(
    state: PairingLinkState,
    onVerify: (String) -> Unit,
    onFetch: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    addressState: PairingAddressState = PairingAddressState.NotRead,
    onRefreshAddresses: () -> Unit = {},
) {
    var link by remember { mutableStateOf("") }
    val line = PairingLinkCopy.lineOf(state)
    val busy = state is PairingLinkState.Fetching
    Scaffold(
        modifier = Modifier.testTag(PairingLinkTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(PairingLinkCopy.TITLE) },
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
            Text(PairingLinkCopy.HINT, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text(PairingLinkCopy.LINK_LABEL) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PairingLinkTags.LINK),
            )
            Button(
                onClick = { onVerify(link) },
                enabled = link.isNotBlank() && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PairingLinkTags.ACTION),
            ) {
                Text(PairingLinkCopy.ACTION)
            }
            if (line != null) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(PairingLinkTags.STATE),
                )
            }
            if (state is PairingLinkState.Parsed || state is PairingLinkState.Fetched) {
                Text(PairingLinkCopy.PARSED_HINT, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = onFetch,
                enabled = (state is PairingLinkState.Parsed || state is PairingLinkState.Fetched) && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PairingLinkTags.FETCH),
            ) {
                Text(PairingLinkCopy.FETCH_ACTION)
            }
            Text(PairingLinkCopy.FETCH_HINT, style = MaterialTheme.typography.bodySmall)
            if (busy) {
                Text(PairingLinkCopy.FETCHING_HINT, style = MaterialTheme.typography.bodySmall)
            }
            if (state is PairingLinkState.Fetched) {
                Text(PairingLinkCopy.FETCHED_HINT, style = MaterialTheme.typography.bodySmall)
            }
            Text(PairingLinkCopy.NOT_YET, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    link = ""
                    onClear()
                },
                enabled = (link.isNotEmpty() || state !is PairingLinkState.Idle) && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PairingLinkTags.CLEAR),
            ) {
                Text("清空")
            }
            Spacer(Modifier.height(16.dp))
            PairingAddressBlock(state = addressState, busy = busy, onRefresh = onRefreshAddresses)
        }
    }
}

/**
 * 「本机可被连的地址」区块（W6-60）：只读展示，**没有任何「开始监听」按钮**。
 *
 * 与 Windows 侧同义：候选地址只变成文字，从不变成 socket。刷新按钮只是重新读一次网卡属性。
 */
@Composable
private fun PairingAddressBlock(
    state: PairingAddressState,
    busy: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PairingLinkTags.ADDRESSES),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(PairingAddressSummary.TITLE, style = MaterialTheme.typography.titleSmall)
        Text(PairingAddressSummary.DISPLAY_ONLY_HINT, style = MaterialTheme.typography.bodySmall)
        when (state) {
            PairingAddressState.NotRead -> Text(
                PairingAddressSummary.NOT_READ_HINT,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(PairingLinkTags.ADDRESS_STATE),
            )
            PairingAddressState.Unavailable -> Text(
                PairingAddressSummary.UNAVAILABLE_HINT,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(PairingLinkTags.ADDRESS_STATE),
            )
            is PairingAddressState.Ready -> {
                Text(
                    PairingAddressSummary.lineOf(state.report),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(PairingLinkTags.ADDRESS_STATE),
                )
                val lines = PairingAddressSummary.linesOf(state.report)
                for (index in lines.indices) {
                    Text(
                        lines[index],
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(PairingLinkTags.ADDRESS_LINE + "." + index),
                    )
                }
            }
        }
        OutlinedButton(
            onClick = onRefresh,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PairingLinkTags.ADDRESS_REFRESH),
        ) {
            Text(PairingAddressSummary.REFRESH)
        }
    }
}
