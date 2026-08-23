package com.mediahub.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.PosterImage
import com.mediahub.core.ui.ThumbImage
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaType

/** 媒体库浏览（本地文件树真实可用；服务端媒体库待 Provider 接入）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoute(
    serverId: String,
    libraryId: String,
    name: String,
    onBack: () -> Unit,
    onOpenLibrary: (MediaLibrary) -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val s = state) {
                            is LibraryUiState.Content -> s.libraryName
                            else -> name.ifBlank { "媒体库" }
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            LibraryUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is LibraryUiState.Libraries -> {
                if (s.libraries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("没有可用媒体库", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(s.libraries, key = { it.id }) { library ->
                            LibraryRow(library = library, onClick = { onOpenLibrary(library) })
                        }
                    }
                }
            }

            is LibraryUiState.Error -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = viewModel::load) { Text("重试") }
                }
            }

            is LibraryUiState.Content -> {
                if (s.items.isEmpty() && !s.isLoadingMore) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    // 海报墙（Phase 1B-2.3）：媒体条目 3 列网格；文件夹保持行，可与网格混排
                    val folders = s.items.filter { it.type == MediaType.FOLDER }
                    val mediaItems = s.items.filter { it.type != MediaType.FOLDER }
                    val gridState = rememberLazyGridState()

                    // 滚动到底部附近时自动触发 loadMore（snapshotFlow 避免 stale s 捕获）
                    LaunchedEffect(Unit) {
                        snapshotFlow {
                            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
                            val totalItems = gridState.layoutInfo.totalItemsCount
                            lastVisible >= totalItems - 3 && s.hasMore && !s.isLoadingMore && s.loadMoreError == null
                        }.collect { shouldLoad ->
                            if (shouldLoad) viewModel.loadMore()
                        }
                    }

                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (s.canGoUp) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                                TextButton(onClick = viewModel::goToParent, modifier = Modifier.fillMaxWidth()) {
                                    Text("⬆ 返回上级")
                                }
                            }
                        }
                        folders.forEach { folder ->
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                                FolderRow(item = folder, onClick = { viewModel.openFolder(folder) })
                            }
                        }
                        gridItems(mediaItems, key = { it.id + ":" + it.title }) { item ->
                            PosterCell(item = item, onClick = { onItemClick(item, viewModel, onOpenItem) })
                        }
                        // 加载更多指示器
                        if (s.isLoadingMore) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(strokeWidth = 2.dp)
                                }
                            }
                        }
                        // loadMore 错误 + 重试
                        if (s.loadMoreError != null) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(s.loadMoreError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = viewModel::loadMore) { Text("加载更多") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun onItemClick(
    item: MediaItem,
    viewModel: LibraryViewModel,
    onOpenItem: (MediaItem) -> Unit,
) {
    // 容器类型（FOLDER/SERIES/SEASON）进入子级；其余（Movie/Episode/Video/Audio）交给播放/详情
    if (item.type.isContainer) {
        viewModel.openFolder(item)
    } else {
        onOpenItem(item)
    }
}

@Composable
private fun LibraryRow(library: MediaLibrary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = when (library.type) {
            com.mediahub.model.LibraryType.MOVIES -> Icons.Default.Movie
            com.mediahub.model.LibraryType.MUSIC -> Icons.Default.MusicNote
            else -> Icons.Default.Folder
        }
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(library.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 文件夹行（混排在海报墙网格中，占满整行）。 */
@Composable
private fun FolderRow(item: MediaItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 海报墙单元格（Phase 1B-2.3）：
 * - 电影/剧集/季 → 2:3 竖版海报；
 * - 单集/视频 → 16:9 缩略图（Episode 的 posterUrl 语义就是 Thumb）；
 * - 无图（provider 未给 posterUrl）→ 灰底占位。
 */
@Composable
private fun PosterCell(item: MediaItem, onClick: () -> Unit) {
    val isThumbShape = item.type == MediaType.EPISODE || item.type == MediaType.VIDEO
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        if (isThumbShape) {
            ThumbImage(
                url = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PosterImage(
                url = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
