package com.mediahub.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.ThumbImageWithProgress
import com.mediahub.feature.server.ServerCard
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress

/** 首页：媒体库（媒体源卡片）+ 继续观看。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    onOpenServer: (MediaServer) -> Unit,
    onRelogin: (MediaServer) -> Unit,
    onAddServer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenItem: (PlaybackProgress) -> Unit,
    forceRestoreId: String? = null,
    onForceRestore: (String) -> Unit = { },
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val authStates by viewModel.authStates.collectAsStateWithLifecycle()

    // re-login 成功后强制刷新该服务器认证状态（评审 FINAL PATCH 3）；消费后清空
    val currentForceRestore = forceRestoreId
    if (currentForceRestore != null) {
        LaunchedEffect(currentForceRestore) {
            viewModel.forceRestore(currentForceRestore)
            onForceRestore(currentForceRestore)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("媒体库") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddServer) {
                Icon(Icons.Default.Add, contentDescription = "添加媒体库")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("媒体源", style = MaterialTheme.typography.titleMedium)
            }
            if (servers.isEmpty()) {
                item {
                    EmptyHint("还没有媒体源\n点击右下角 + 添加 Emby / Jellyfin / WebDAV / 本地存储")
                }
            } else {
                items(servers, key = { it.id }) { server ->
                    val needsRelogin = viewModel.needsRelogin(server, authStates[server.id])
                    ServerCard(
                        server = server,
                        onClick = {
                            if (needsRelogin) onRelogin(server) else onOpenServer(server)
                        },
                        authState = authStates[server.id],
                        onLogout = { viewModel.logout(server.id) },
                    )
                }
            }

            item {
                Text(
                    "继续观看",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (continueWatching.isEmpty()) {
                item { EmptyHint("暂无播放记录") }
            } else {
                items(continueWatching, key = { "${it.serverId}/${it.itemId}" }) { progress ->
                    ContinueWatchingRow(progress = progress, onClick = { onOpenItem(progress) })
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingRow(
    progress: PlaybackProgress,
    onClick: () -> Unit,
) {
    // 16:9 缩略图 + 底部进度条（Phase 1B-2.3）；旧记录无 posterUrl 时显示占位图
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (fraction * 100).toInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        ThumbImageWithProgress(
            url = progress.posterUrl,
            contentDescription = progress.itemTitle,
            progress = fraction,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = progress.itemTitle ?: "未命名",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "进度 $percent%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
