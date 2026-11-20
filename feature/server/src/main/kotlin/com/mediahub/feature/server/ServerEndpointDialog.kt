package com.mediahub.feature.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediahub.model.ServerEndpoint

/** 线路切换弹窗：只允许在已启用线路之间切换当前生效线路。 */
@Composable
internal fun ServerEndpointDialog(
    endpoints: List<ServerEndpoint>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换线路") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "选择当前生效线路；已停用的线路不可选。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                endpoints.sortedBy { it.sortOrder }.forEach { endpoint ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = endpoint.enabled) { onSelect(endpoint.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = endpoint.isPrimary,
                            onClick = { if (endpoint.enabled) onSelect(endpoint.id) },
                            enabled = endpoint.enabled,
                        )
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(endpoint.name, style = MaterialTheme.typography.bodyMedium)
                                if (!endpoint.enabled) {
                                    Text(
                                        "（已停用）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Text(
                                endpoint.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            endpoint.lastApiLatencyMs?.let { latency ->
                                Text(
                                    "上次 API 延迟：${latency}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
