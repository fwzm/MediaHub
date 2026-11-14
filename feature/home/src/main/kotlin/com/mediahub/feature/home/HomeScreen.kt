package com.mediahub.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.MediaHubAccentLine
import com.mediahub.core.ui.MediaHubCard
import com.mediahub.core.ui.MediaHubLabel
import com.mediahub.core.ui.MediaHubSectionHeader
import com.mediahub.core.ui.MediaHubStatus
import com.mediahub.core.ui.ThumbImageWithProgress
import com.mediahub.core.ui.nav.GlassBackdropRecorder
import com.mediahub.core.ui.nav.LiquidGlassNavBar
import com.mediahub.core.ui.nav.NAV_BAR_INSET_DP
import com.mediahub.core.ui.nav.NavBarItemSpec
import com.mediahub.core.ui.nav.glassBackdropSource
import com.mediahub.feature.server.ServerCard
import com.mediahub.model.HomeSectionIds
import com.mediahub.model.HomeSectionStyle
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
    forceRestoreId: String? = null,
    onForceRestore: (String) -> Unit = { },
    viewModel: HomeViewModel = hiltViewModel(),
    appearance: HomeAppearanceViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val authStates by viewModel.authStates.collectAsStateWithLifecycle()
    val sectionStyles by appearance.sectionStyles.collectAsStateWithLifecycle()

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
        sectionStyles = sectionStyles,
        onCycleSectionStyle = appearance::cycleStyle,
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
    sectionStyles: Map<String, HomeSectionStyle> = emptyMap(),
    onCycleSectionStyle: (String) -> Unit = {},
) {
    // 条目 29 收尾：API 31+ 用真背板模糊。首页列表的绘制被顺便录进 RenderNode，
    // 导航栏按两者在 root 的坐标差取「自己身后」那一块做模糊；低版本可选性为 false，保持仿制方案。
    val backdropRecorder = remember { GlassBackdropRecorder() }
    DisposableEffect(backdropRecorder) {
        onDispose { backdropRecorder.release() }
    }
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }
    var navBarOrigin by remember { mutableStateOf(Offset.Zero) }

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
        bottomBar = {
            // 条目 29：液态玻璃导航栏，选中态即菜单高亮（70）；放在 Scaffold 里让内容与 FAB 自动让位。
            LiquidGlassNavBar(
                items = homeNavItems,
                selectedId = HOME_NAV_ID,
                onSelect = { id ->
                    when (id) {
                        NAV_SEARCH_ID -> onOpenSearch()
                        NAV_SETTINGS_ID -> onOpenSettings()
                    }
                },
                iconOf = { item -> homeNavIcon(item.id) },
                modifier = Modifier
                    .onGloballyPositioned { navBarOrigin = it.positionInRoot() }
                    .padding(horizontal = NAV_BAR_INSET_DP.dp, vertical = 8.dp),
                backdrop = backdropRecorder,
                backdropOffset = contentOrigin - navBarOrigin,
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
        val continueStyle = sectionStyleOf(sectionStyles, HomeSectionIds.CONTINUE_WATCHING)
        val serverStyle = sectionStyleOf(sectionStyles, HomeSectionIds.SERVERS)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .onGloballyPositioned { contentOrigin = it.positionInRoot() }
                .glassBackdropSource(backdropRecorder),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 10.dp,
                end = 20.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(contentType = "intro") {
                Text(
                    "你的媒体，尽在一处",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (continueWatching.isNotEmpty()) {
                item(contentType = "continueHeader") {
                    MediaHubSectionHeader(
                        title = "继续观看",
                        subtitle = "从上次离开的地方继续 · ${homeStyleLabel(continueStyle)}",
                        modifier = Modifier.padding(top = 12.dp),
                        action = {
                            IconButton(onClick = { onCycleSectionStyle(HomeSectionIds.CONTINUE_WATCHING) }) {
                                Icon(Icons.Default.List, contentDescription = "切换继续观看卡片样式")
                            }
                        },
                    )
                }
                if (continueStyle == HomeSectionStyle.ROW) {
                    item(contentType = "continueRows") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            continueWatching.take(4).forEach { progress ->
                                ContinueWatchingCard(
                                    progress = progress,
                                    onClick = { onOpenItem(progress) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                } else {
                    item(contentType = "continueCarousel") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(continueWatching, key = { "${it.serverId}/${it.itemId}" }) { progress ->
                                ContinueWatchingCard(
                                    progress = progress,
                                    onClick = { onOpenItem(progress) },
                                    modifier = Modifier.width(continueCardWidthDp(continueStyle).dp),
                                )
                            }
                        }
                    }
                }
            }

            item(contentType = "serverHeader") {
                MediaHubSectionHeader(
                    title = "媒体源",
                    subtitle = "${
                        if (servers.isEmpty()) "连接你的媒体世界" else "${servers.size} 个媒体源"
                    } · ${homeStyleLabel(serverStyle)}",
                    modifier = Modifier.padding(top = if (continueWatching.isEmpty()) 16.dp else 24.dp),
                    action = {
                        IconButton(onClick = { onCycleSectionStyle(HomeSectionIds.SERVERS) }) {
                            Icon(Icons.Default.List, contentDescription = "切换媒体源卡片样式")
                        }
                    },
                )
            }
            if (!loaded) {
                item(contentType = "status") {
                    MediaHubStatus(
                        title = "正在整理媒体库",
                        message = "片刻就好",
                        loading = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (servers.isEmpty()) {
                item(contentType = "status") {
                    MediaHubStatus(
                        title = "从添加媒体源开始",
                        message = "支持 Emby、Jellyfin、WebDAV 和本地存储。连接后，在这里统一浏览与播放。",
                        icon = Icons.Default.PlayArrow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                items(servers, key = { it.id }, contentType = { "server" }) { server ->
                    ServerCard(
                        server = server,
                        onClick = {
                            if (needsRelogin(server)) onRelogin(server) else onOpenServer(server)
                        },
                        authState = authState(server),
                        onLogout = { onLogout(server) },
                        onEdit = { onEditServer(server) },
                        compact = serverStyle != HomeSectionStyle.CARD,
                    )
                }
            }

            if (continueWatching.isEmpty()) {
                item(contentType = "status") {
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

@Composable
private fun ContinueWatchingCard(    progress: PlaybackProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val remainingMinutes = ((progress.durationMs - progress.positionMs).coerceAtLeast(0L) / 60_000).toInt()
    MediaHubCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(10.dp),
    ) {
        androidx.compose.foundation.layout.Box {
            ThumbImageWithProgress(
                url = progress.posterUrl,
                contentDescription = progress.itemTitle,
                progress = fraction,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.9f),
                            ),
                        ),
                    )
                    .padding(start = 12.dp, top = 32.dp, end = 12.dp, bottom = 14.dp),
            ) {
                Text(
                    text = progress.itemTitle ?: "未命名",
                    style = MaterialTheme.typography.titleSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaHubLabel(text = "已看 ${(fraction * 100).toInt()}%", emphasized = true)
                    if (remainingMinutes > 0) {
                        Text(
                            "剩余约 $remainingMinutes 分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                        )
                    }
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }
    }
}

/** 样式中文标签（用户口径），用于区块副标题。 */
internal fun homeStyleLabel(style: HomeSectionStyle): String = when (style) {
    HomeSectionStyle.CARD -> "大卡片"
    HomeSectionStyle.COMPACT -> "紧凑"
    HomeSectionStyle.ROW -> "列表"
}

// 底部导航（条目 29）：首页为当前页，搜索/设置是跳转项。
internal const val HOME_NAV_ID = "home"
internal const val NAV_SEARCH_ID = "search"
internal const val NAV_SETTINGS_ID = "settings"

private val homeNavItems = listOf(
    NavBarItemSpec(id = HOME_NAV_ID, label = "首页"),
    NavBarItemSpec(id = NAV_SEARCH_ID, label = "搜索"),
    NavBarItemSpec(id = NAV_SETTINGS_ID, label = "设置"),
)

private fun homeNavIcon(id: String) = when (id) {
    NAV_SEARCH_ID -> Icons.Default.Search
    NAV_SETTINGS_ID -> Icons.Default.Settings
    else -> Icons.Default.Home
}
