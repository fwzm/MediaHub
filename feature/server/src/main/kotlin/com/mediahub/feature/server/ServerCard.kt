package com.mediahub.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState

/**
 * 媒体源卡片（首页"媒体库"与详情共用）。
 * [authState] 为该服务器当前登录态（null = 不支持认证）；[onLogout] 提供退出入口。
 */
@Composable
fun ServerCard(
    server: MediaServer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    authState: AuthSessionState? = null,
    onLogout: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = server.type.label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.baseUrl.ifBlank { "本地存储" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                server.lastError?.let { error ->
                    Text(
                        text = "上次错误：$error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                authState?.let { AuthStatusLine(it) }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (server.isDefault) {
                    Text(
                        text = "默认",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (onEdit != null) {
                    TextButton(onClick = onEdit) { Text("编辑", style = MaterialTheme.typography.labelMedium) }
                }
                if (onLogout != null && authState is AuthSessionState.Authenticated) {
                    TextButton(onClick = onLogout) { Text("退出", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

/** 登录态状态行（review：用户可见登录态）。 */
@Composable
private fun AuthStatusLine(state: AuthSessionState) {
    val (text, color) = when (state) {
        is AuthSessionState.Authenticated ->
            "已登录：${state.user.displayName}" to MaterialTheme.colorScheme.primary

        AuthSessionState.Restoring ->
            "恢复登录中…" to MaterialTheme.colorScheme.onSurfaceVariant

        AuthSessionState.SignedOut, AuthSessionState.Unknown ->
            "未登录" to MaterialTheme.colorScheme.onSurfaceVariant

        is AuthSessionState.Error -> when (state.kind) {
            AuthSessionErrorKind.SESSION_EXPIRED,
            AuthSessionErrorKind.SERVER_MISMATCH,
            -> "${state.message}（点击重新登录）" to MaterialTheme.colorScheme.error

            else -> state.message to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
