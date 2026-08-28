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
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.mediahub.model.MediaSort
import com.mediahub.model.MediaSortField
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
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    val sortEntryVisible = (state as? LibraryUiState.Content)?.sortFields?.isNotEmpty() == true

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
                    if (sortEntryVisible) {
                        IconButton(onClick = { showSortSheet = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "排序")
                        }
                    }
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
                    val gridState = rememberLazyGridState()

                    // 滚动到底部自动 loadMore（rememberUpdatedState 避免 stale s 捕获）
                    val currentContent by rememberUpdatedState(s)
                    LaunchedEffect(Unit) {
                        snapshotFlow {
                            val c = currentContent
                            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
                            val totalItems = gridState.layoutInfo.totalItemsCount
                            lastVisible >= totalItems - 3 && c.hasMore && !c.isLoadingMore && c.loadMoreError == null
                        }.distinctUntilChanged().collect { shouldLoad ->
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
                        // 返回上级：导航控件固定顶部，不参与排序（C2）
                        if (s.canGoUp) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                                TextButton(onClick = viewModel::goToParent, modifier = Modifier.fillMaxWidth()) {
                                    Text("⬆ 返回上级")
                                }
                            }
                        }
                        if (shouldPreserveProviderOrder(s.sort)) {
                            // 用户主动选择排序后：Provider 返回顺序即权威顺序，
                            // UI 不得再按目录/媒体拆分重排（那会覆盖服务端全局排序）
                            gridItems(
                                s.items,
                                key = { it.id },
                                span = { item ->
                                    if (item.type == MediaType.FOLDER) {
                                        androidx.compose.foundation.lazy.grid.GridItemSpan(3)
                                    } else {
                                        androidx.compose.foundation.lazy.grid.GridItemSpan(1)
                                    }
                                },
                            ) { item ->
                                if (item.type == MediaType.FOLDER) {
                                    FolderRow(item = item, onClick = { viewModel.openFolder(item) })
                                } else {
                                    PosterCell(item = item, onClick = { onItemClick(item, viewModel, onOpenItem) })
                                }
                            }
                        } else {
                            // SERVER_DEFAULT：保留既有"目录优先"展示策略
                            val folders = s.items.filter { it.type == MediaType.FOLDER }
                            val mediaItems = s.items.filter { it.type != MediaType.FOLDER }
                            folders.forEach { folder ->
                                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                                    FolderRow(item = folder, onClick = { viewModel.openFolder(folder) })
                                }
                            }
                            gridItems(mediaItems, key = { it.id }) { item ->
                                PosterCell(item = item, onClick = { onItemClick(item, viewModel, onOpenItem) })
                            }
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

    // 排序面板（1C-2）：选项按 Provider 能力过滤；即时生效，不做本地排序
    val sortContent = state as? LibraryUiState.Content
    if (showSortSheet && sortContent != null) {
        ModalBottomSheet(onDismissRequest = { showSortSheet = false }) {
            SortSheetContent(
                current = sortContent.sort,
                fields = sortContent.sortFields,
                onSelected = viewModel::onSortSelected,
            )
        }
    }
}

/**
 * C2 排序展示策略：用户主动选择排序（非 SERVER_DEFAULT）后，Provider 返回顺序即权威顺序，
 * UI 不得按目录/媒体拆分重排（分组会覆盖服务端全局排序）；SERVER_DEFAULT 保留既有
 * "目录优先"策略。返回上级等导航控件固定顶部，不参与两种策略。
 */
internal fun shouldPreserveProviderOrder(sort: MediaSort): Boolean =
    sort.field != MediaSortField.SERVER_DEFAULT

/** 排序选项中文标签（用户口径）。 */
internal fun sortLabel(field: MediaSortField): String = when (field) {    MediaSortField.SERVER_DEFAULT -> "默认"
    MediaSortField.DATE_ADDED -> "加入日期"
    MediaSortField.TITLE -> "标题"
    MediaSortField.COMMUNITY_RATING -> "公众评分"
    MediaSortField.CRITIC_RATING -> "影评人评分"
    MediaSortField.PRODUCTION_YEAR -> "出品年份"
    MediaSortField.PREMIERE_DATE -> "首映日期"
    MediaSortField.OFFICIAL_RATING -> "官方评级"
    MediaSortField.RUNTIME -> "播放时长"
    MediaSortField.BITRATE -> "比特率"
    MediaSortField.SIZE -> "大小"
    MediaSortField.RANDOM -> "随机"
}

@Composable
private fun SortSheetContent(
    current: MediaSort,
    fields: List<MediaSortField>,
    onSelected: (MediaSort) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
    ) {
        Text(
            "排序方式",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        fields.forEach { field ->
            // 无方向语义字段固定 ASC 方向值（引擎/Provider 会忽略 direction）
            SortOptionRow(
                label = sortLabel(field),
                selected = current.field == field,
                onClick = {
                    onSelected(
                        if (current.field == field && current.hasDirection) {
                            current
                        } else {
                            MediaSort(field)
                        }
                    )
                },
            )
        }

        // 升序 / 降序：仅对有方向语义的字段展示
        if (current.hasDirection) {
            Text(
                "方向",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            listOf(
                MediaHubSortDirectionOption("升序", com.mediahub.model.SortDirection.ASC),
                MediaHubSortDirectionOption("降序", com.mediahub.model.SortDirection.DESC),
            ).forEach { option ->
                SortOptionRow(
                    label = option.label,
                    selected = current.direction == option.direction,
                    onClick = { onSelected(MediaSort(current.field, option.direction)) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private data class MediaHubSortDirectionOption(val label: String, val direction: com.mediahub.model.SortDirection)

@Composable
private fun SortOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
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
