package com.mediahub.feature.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * 统一服务器地址输入组件（Phase 1I：添加媒体库 / 重新登录 / 服务器与线路编辑共用）。
 *
 * - HTTPS 开关：无显式协议输入按开关补全；粘贴完整 URL 时由 ViewModel 依据
 *   [ServerAddressNormalizer.explicitHttpScheme] 回写开关（粘贴内容为准）；
 *   手动切换只改 scheme，显式端口与反代子路径由规范化保留。
 * - 实时预览"实际连接地址"；[errorMessage] 非空时字段进入错误态。
 * - 地址输入禁用自动更正与自动首字母大写（GBoard 改写输入问题）。
 */
@Composable
fun ServerAddressField(
    value: String,
    onValueChange: (String) -> Unit,
    preferHttps: Boolean,
    onHttpsChange: (Boolean) -> Unit,
    resolvedUrl: String?,
    errorMessage: String?,
    modifier: Modifier = Modifier,
    placeholder: String = "media.example:8920",
    httpsSwitchEnabled: Boolean = true,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器地址") },
            placeholder = { Text(placeholder) },
            singleLine = true,
            isError = errorMessage != null,
            supportingText = { errorMessage?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "使用 HTTPS",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = preferHttps,
                onCheckedChange = onHttpsChange,
                enabled = httpsSwitchEnabled,
            )
        }
        if (!preferHttps) {
            Text(
                text = "连接未加密",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (errorMessage == null) {
            resolvedUrl?.let { resolved ->
                Text(
                    text = "实际连接地址：$resolved",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 地址与用户名共用的输入 KeyboardOptions（禁自动更正/首字母大写，GBoard 改写问题）。 */
val ServerTextInputOptions = KeyboardOptions(
    keyboardType = KeyboardType.Uri,
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
)
