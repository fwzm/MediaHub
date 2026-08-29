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
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.PosterImage
import com.mediahub.core.ui.ThumbImage
import com.mediahub.model.MediaFilter
import com.mediahub.model.MediaFilterField
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
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    val filterContent = state as? LibraryUiState.Content
    val sortEntryVisible = filterContent?.sortFields?.isNotEmpty() == true
    val filterEntryVisible = filterContent?.filterFields?.isNotEmpty() == true
    val filterActive = filterContent?.filter?.isDefault == false

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
                    if (filterEntryVisible) {
                        IconButton(onClick = { showFilterSheet = true }) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = if (filterActive) "筛选（已启用）" else "筛选",
                                tint = if (filterActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                    }
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

    // 筛选面板（1D）：container-scoped、即时生效；选项按 Provider 能力过滤
    if (showFilterSheet && filterContent != null) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            FilterSheetContent(
                current = filterContent.filter,
                fields = filterContent.filterFields,
                onSelected = viewModel::onFilterSelected,
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

/**
 * 年份草稿 → 新 [MediaFilter]；返回 null = 草稿未构成可提交的合法年份
 * （或与现值相同），不触发重载。空串 = 清除年份（恢复"全部"）。
 */
internal fun yearDraftToFilter(current: MediaFilter, draft: String): MediaFilter? = when {
    draft.isEmpty() -> if (current.year == null) null else current.copy(year = null)
    draft.length == 4 && draft.all(Char::isDigit) -> {
        // toIntOrNull：非法/越界四位数字（如 "0000" 违反 domain require(year>0)）不得炸 UI——
        // domain invariant 不放宽，非法草稿一律按 no-op 丢弃
        val year = draft.toIntOrNull()
        if (year == null || year <= 0 || year == current.year) null else current.copy(year = year)
    }
    else -> null
}

internal fun mediaTypeFilterLabel(value: MediaType?): String = when (value) {
    null -> "全部"
    MediaType.MOVIE -> "电影"
    MediaType.SERIES -> "剧集"
    MediaType.EPISODE -> "单集"
    else -> "全部"
}

internal fun playedFilterLabel(value: Boolean?): String = when (value) {
    null -> "全部"
    true -> "已看"
    false -> "未看"
}

internal fun favoriteFilterLabel(value: Boolean?): String = when (value) {
    null -> "全部"
    true -> "已收藏"
    false -> "未收藏"
}

/** 排序选项中文标签（用户口径）。 */
internal fun sortLabel(field: MediaSortField): String = when (field) {
    MediaSortField.SERVER_DEFAULT -> "默认"
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

/** 筛选面板（1D）：类型/年份/已看/收藏；全部字段 tri-state 首项"全部"；年份数字输入带草稿语义。 */
@Composable
private fun FilterSheetContent(
    current: MediaFilter,
    fields: List<MediaFilterField>,
    onSelected: (MediaFilter) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("筛选", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!current.isDefault) {
                TextButton(onClick = { onSelected(MediaFilter()) }) { Text("清除全部") }
            }
        }

        if (MediaFilterField.MEDIA_TYPE in fields) {
            SheetSectionLabel("类型")
            listOf<MediaType?>(null, MediaType.MOVIE, MediaType.SERIES, MediaType.EPISODE).forEach { value ->
                FilterOptionRow(
                    label = mediaTypeFilterLabel(value),
                    selected = current.mediaType == value,
                    onClick = { onSelected(current.copy(mediaType = value)) },
                )
            }
        }

        if (MediaFilterField.YEAR in fields) {
            SheetSectionLabel("年份")
            // 外部变化（清除全部/导航重置）时草稿同步
            var yearDraft by remember(current.year) { mutableStateOf(current.year?.toString() ?: "") }
            OutlinedTextField(
                value = yearDraft,
                onValueChange = { raw ->
                    val sanitized = raw.filter(Char::isDigit).take(4)
                    yearDraft = sanitized
                    // 只有空串（清除）或恰好四位合法年份才提交；中间态只留草稿不发请求
                    yearDraftToFilter(current, sanitized)?.let(onSelected)
                },
                label = { Text("如 2024，留空 = 全部") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }

        if (MediaFilterField.PLAYED in fields) {
            SheetSectionLabel("已看状态")
            listOf<Boolean?>(null, true, false).forEach { value ->
                FilterOptionRow(
                    label = playedFilterLabel(value),
                    selected = current.played == value,
                    onClick = { onSelected(current.copy(played = value)) },
                )
            }
        }

        if (MediaFilterField.FAVORITE in fields) {
            SheetSectionLabel("收藏")
            listOf<Boolean?>(null, true, false).forEach { value ->
                FilterOptionRow(
                    label = favoriteFilterLabel(value),
                    selected = current.favorite == value,
                    onClick = { onSelected(current.copy(favorite = value)) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun FilterOptionRow(
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
