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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 配对页的测试标签（界面测试与文档共用）。
 */
object PairingLinkTags {

    const val SCREEN: String = "pairing.screen"

    const val LINK: String = "pairing.link"

    const val ACTION: String = "pairing.action"

    const val STATE: String = "pairing.state"

    const val CLEAR: String = "pairing.clear"
}

/**
 * 配对入口（W6-59）：在设置页内托管，返回即回设置列表。
 *
 * 不注入任何依赖：核对是纯 Kotlin 的一次解析，控制器随页面 remember 即可。
 */
@Composable
fun PairingLinkRoute(
    onBack: () -> Unit,
    controller: PairingLinkController = remember { PairingLinkController() },
) {
    val state by controller.state.collectAsStateWithLifecycle()
    PairingLinkScreen(
        state = state,
        onVerify = { link -> controller.verify(link) },
        onClear = { controller.clear() },
        onBack = onBack,
    )
}

/**
 * 配对页（无状态）：贴链接 → 核对 → 显示设备标识 / 公钥指纹 / 剩余有效期。
 *
 * 页面里写明「本版本不连接对端」，避免用户把「核对成功」当成「已经配好」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingLinkScreen(
    state: PairingLinkState,
    onVerify: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var link by remember { mutableStateOf("") }
    val line = PairingLinkCopy.lineOf(state)
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PairingLinkTags.LINK),
            )
            Button(
                onClick = { onVerify(link) },
                enabled = link.isNotBlank(),
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
            if (state is PairingLinkState.Parsed) {
                Text(PairingLinkCopy.PARSED_HINT, style = MaterialTheme.typography.bodySmall)
            }
            Text(PairingLinkCopy.NOT_YET, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    link = ""
                    onClear()
                },
                enabled = link.isNotEmpty() || state !is PairingLinkState.Idle,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PairingLinkTags.CLEAR),
            ) {
                Text("清空")
            }
        }
    }
}
