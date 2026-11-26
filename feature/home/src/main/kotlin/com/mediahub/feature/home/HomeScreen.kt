package com.mediahub.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.MediaHubAccentLine
import com.mediahub.core.ui.MediaHubSectionHeader
import com.mediahub.core.ui.MediaHubStatus
import com.mediahub.feature.server.ServerCard
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    onOpenServer: (MediaServer) -> Unit,
    onRelogin: (MediaServer) -> Unit,
    onEditServer: (MediaServer) -> Unit,
    onAddServer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenItem: (PlaybackProgress) -> Unit,
    onOpenAllContinueWatching: (serverId: String, label: String) -> Unit = { _, _ -> },
    forceRestoreId: String? = null,
    onForceRestore: (String) -> Unit = { },
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val continueWatching by viewModel.visibleContinueWatching.collectAsStateWithLifecycle()
    val sourceOptions by viewModel.sourceOptions.collectAsStateWithLifecycle()
    val homeServerId by viewModel.homeServerId.collectAsStateWithLifecycle()
    val isTemporaryHome by viewModel.isTemporaryHome.collectAsStateWithLifecycle()
    val serversFailed by viewModel.serversFailed.collectAsStateWithLifecycle()
    val continueWatchingFailed by viewModel.continueWatchingFailed.collectAsStateWithLifecycle()
    val authStates by viewModel.authStates.collectAsStateWithLifecycle()

    val currentForceRestore = forceRestoreId
    if (currentForceRestore != null) {
        LaunchedEffect(currentForceRestore) {
            viewModel.forceRestore(currentForceRestore)
            onForceRestore(currentForceRestore)
        }
    }

    HomeContent(
        servers = servers,
        loaded = loaded,
        continueWatching = continueWatching,
        needsRelogin = { server -> viewModel.needsRelogin(server, authStates[server.id]) },
        authState = { server -> authStates[server.id] },
        onOpenServer = onOpenServer,
        onRelogin = onRelogin,
        onEditServer = onEditServer,
        onLogout = { server -> viewModel.logout(server.id) },
        onAddServer = onAddServer,
        onOpenSettings = onOpenSettings,
        onOpenSearch = onOpenSearch,
        onOpenItem = onOpenItem,
        sourceOptions = sourceOptions,
        homeServerId = homeServerId,
        isTemporaryHome = isTemporaryHome,
        serversFailed = serversFailed,
        continueWatchingFailed = continueWatchingFailed,
        onSetAsHome = viewModel::setAsHome,
        onRestoreDefaultHome = viewModel::restoreDefaultHome,
        onOpenAllContinueWatching = onOpenAllContinueWatching,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    servers: List<MediaServer>,
    loaded: Boolean,
    continueWatching: List<PlaybackProgress>,
    needsRelogin: (MediaServer) -> Boolean,
    authState: (MediaServer) -> com.mediahub.provider.api.AuthSessionState?,
    onOpenServer: (MediaServer) -> Unit,
    onRelogin: (MediaServer) -> Unit,
    onEditServer: (MediaServer) -> Unit,
    onLogout: (MediaServer) -> Unit,
    onAddServer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenItem: (PlaybackProgress) -> Unit,
    sourceOptions: List<HomeSourceOption> = emptyList(),
    homeServerId: String? = null,
    isTemporaryHome: Boolean = false,
    serversFailed: Boolean = false,
    continueWatchingFailed: Boolean = false,
    onSetAsHome: (String) -> Unit = {},
    onRestoreDefaultHome: () -> Unit = {},
    onOpenAllContinueWatching: (serverId: String, label: String) -> Unit = { _, _ -> },
) {
    val selectedSource = sourceOptions.firstOrNull { it.serverId == homeServerId }
    val showContinueWatching = servers.isNotEmpty() || continueWatching.isNotEmpty() || continueWatchingFailed

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MediaHubAccentLine()
                        Text("MediaHub", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(onClick = onAddServer) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加媒体库")
                    Text("添加媒体库")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "你的媒体，尽在一处",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showContinueWatching) {
                item {
                    MediaHubSectionHeader(
                        title = "继续观看",
                        subtitle = selectedSource?.let { "当前首页：${it.label}" } ?: "从上次离开的地方继续",
                        modifier = Modifier.padding(top = 12.dp),
                        action = {
                            if (selectedSource != null) {
                                if (isTemporaryHome) {
                                    TextButton(onClick = onRestoreDefaultHome) {
                                        Text("恢复默认", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                TextButton(
                                    onClick = { onOpenAllContinueWatching(selectedSource.serverId, selectedSource.label) },
                                ) {
                                    Text("查看全部", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        },
                    )
                }
                if (sourceOptions.isNotEmpty()) {
                    item {
                        // 数据源选择器只显示线路名称（条目 144），点击即临时切换首页（条目 117）。
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(sourceOptions, key = { it.serverId }) { option ->
                                FilterChip(
                                    selected = option.serverId == homeServerId,
                                    onClick = { onSetAsHome(option.serverId) },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                    }
                }
                when {
                    continueWatchingFailed -> item {
                        MediaHubStatus(
                            title = "继续观看加载失败",
                            message = "媒体源分区不受影响，稍后重进首页会再次尝试。",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    continueWatching.isNotEmpty() -> item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(continueWatching, key = { "${it.serverId}/${it.itemId}" }) { progress ->
                                ContinueWatchingCard(
                                    progress = progress,
                                    onClick = { onOpenItem(progress) },
                                    modifier = Modifier.width(276.dp),
                                )
                            }
                        }
                    }

                    shouldShowNoPlayableResources(servers, continueWatching) -> item {
                        // 资源聚合空态（条目 148）：来源存在但没有可继续播放的内容。
                        MediaHubStatus(
                            title = NO_PLAYABLE_RESOURCES,
                            message = "换个媒体源，或先去媒体库挑一部开始播放。",
                            icon = Icons.Default.PlayArrow,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                MediaHubSectionHeader(
                    title = "媒体源",
                    subtitle = if (servers.isEmpty()) "连接你的媒体世界" else "${servers.size} 个媒体源",
                    modifier = Modifier.padding(top = if (showContinueWatching) 24.dp else 16.dp),
                )
            }
            if (!loaded) {
                item {
                    MediaHubStatus(
                        title = "正在整理媒体库",
                        message = "片刻就好",
                        loading = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (serversFailed) {
                item {
                    MediaHubStatus(
                        title = "媒体源加载失败",
                        message = "继续观看分区不受影响，稍后重进首页会再次尝试。",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (servers.isEmpty()) {
                item {
                    MediaHubStatus(
                        title = "从添加媒体源开始",
                        message = "支持 Emby、Jellyfin、WebDAV 和本地存储。连接后，在这里统一浏览与播放。",
                        icon = Icons.Default.PlayArrow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                items(servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        onClick = {
                            if (needsRelogin(server)) onRelogin(server) else onOpenServer(server)
                        },
                        authState = authState(server),
                        onLogout = { onLogout(server) },
                        onEdit = { onEditServer(server) },
                        isHome = server.id == homeServerId,
                        onSetAsHome = if (server.id == homeServerId) {
                            null
                        } else {
                            ({ onSetAsHome(server.id) })
                        },
                    )
                }
            }

            if (!showContinueWatching) {
                item {
                    MediaHubStatus(
                        title = "准备好看点什么？",
                        message = "播放记录会出现在这里，随时继续。",
                        icon = Icons.Default.PlayArrow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
