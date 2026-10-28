package com.mediahub.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.MediaHubCard
import com.mediahub.core.ui.MediaHubLabel
import com.mediahub.core.ui.MediaHubStatus
import com.mediahub.core.ui.PosterImage
import com.mediahub.core.ui.ThumbImage
import com.mediahub.feature.search.engine.GlobalSearchState
import com.mediahub.feature.search.engine.SearchAggregator
import com.mediahub.feature.search.engine.SearchResultEntry
import com.mediahub.feature.search.engine.UnifiedSearchHit
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType

/**
 * 全局聚合搜索（Phase 1C-1）。
 *
 * - 结果 = 所有具备 SEARCH 能力数据源的合并流（partial success，见 GlobalSearchEngine）。
 * - 点击命中统一进入 DetailRoute（onOpenItem）：
 *   Movie/Episode → 详情播放；Series → Series Detail（季 chips + EpisodeRow）。
 *   Library 浏览的 SERIES 下钻语义保持不变，搜索是 Series Detail 页面的正式入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    viewModel: GlobalSearchViewModel = hiltViewModel(),
) {
    // The field and search engine share the same restorable query.
    val input by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    SearchContent(
        query = input,
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onBack = onBack,
        onOpenItem = onOpenItem,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    query: String,
    state: GlobalSearchState,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenItem: (MediaItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        placeholder = {
                            Text(
                                "搜索电影 / 剧集 / 单集",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        singleLine = true,
                        shape = CircleShape,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "清空")
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        SearchBody(
            state = state,
            onOpenItem = onOpenItem,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
fun SearchBody(
    state: GlobalSearchState,
    onOpenItem: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 聚合为纯同步投影：与 state 来自同一 Compose snapshot，杜绝 stale entries 帧闪烁
    val entries = remember(state.hits) { SearchAggregator.group(state.hits) }
    if (state.query.isBlank()) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MediaHubStatus(
                title = "探索你的媒体世界",
                message = "输入关键词，聚合搜索所有媒体源",
                icon = Icons.Default.Search,
            )
        }
        return
    }

    // MultiSource 展开状态：手风琴式（同时最多展开一张聚合卡）
    var expandedEntryKey by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = 16.dp, vertical = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { searchEntryKey(it) }) { entry ->
            when (entry) {
                is SearchResultEntry.Single ->
                    SearchHitRow(hit = entry.hit, onClick = { onOpenItem(entry.hit.item) })

                is SearchResultEntry.MultiSource -> {
                    val key = searchEntryKey(entry)
                    if (expandedEntryKey == key) {
                        // 展开态：成员行逐条渲染（保持 occurrences 原序），点击成员走现有导航
                        entry.occurrences.forEach { hit ->
                            SearchHitRow(
                                hit = UnifiedSearchHit(item = hit.item, serverName = hit.serverName),
                                onClick = { onOpenItem(hit.item) },
                            )
                        }
                    } else {
                        MultiSourceCard(
                            entry = entry,
                            onClick = { expandedEntryKey = key },
                        )
                    }
                }
            }
        }

        // 逐服务器状态行：进行中 / 失败（partial success 不打断结果流）
        if (state.isSearching || state.errors.isNotEmpty()) {
            item { ServerStatusLines(state) }
        }

        if (!state.isSearching && state.hits.isEmpty() && state.errors.isEmpty()) {
            item {
                MediaHubStatus(
                    title = "没有找到匹配内容",
                    message = "没有找到与「${state.query}」匹配的内容，试试其他关键词",
                    icon = Icons.Default.Search,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** LazyColumn 稳定 key：Single 用 (serverId, id)；MultiSource 用 canonical 别名集。 */
internal fun searchEntryKey(entry: SearchResultEntry): String = when (entry) {
    is SearchResultEntry.Single -> "hit:${entry.hit.item.serverId}/${entry.hit.item.id}"
    is SearchResultEntry.MultiSource ->
        "agg:" + entry.identityKeys.sortedBy { "${it.type}/${it.provider}/${it.value}" }
            .joinToString("|") { "${it.type}/${it.provider}/${it.value}" }
}

/** 多来源聚合卡（Phase 1E）：元数据取首个 occurrence；点击展开成员行。 */
@Composable
private fun MultiSourceCard(
    entry: SearchResultEntry.MultiSource,
    onClick: () -> Unit,
) {
    val first = entry.occurrences.first().item
    val isThumbShape = first.type == MediaType.EPISODE || first.type == MediaType.VIDEO
    MediaHubCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (isThumbShape) {
                ThumbImage(
                    url = first.posterUrl,
                    contentDescription = first.title,
                    modifier = Modifier.width(120.dp),
                )
            } else {
                PosterImage(
                    url = first.posterUrl,
                    contentDescription = first.title,
                    modifier = Modifier.width(96.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    first.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    first.year?.let { add(it.toString()) }
                    add(typeLabel(first.type))
                }
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MediaHubLabel(text = "${entry.sourceCount} 个来源", emphasized = true)
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: UnifiedSearchHit, onClick: () -> Unit) {
    val item = hit.item
    val isThumbShape = item.type == MediaType.EPISODE || item.type == MediaType.VIDEO
    MediaHubCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (isThumbShape) {
                ThumbImage(
                    url = item.posterUrl,
                    contentDescription = item.title,
                    modifier = Modifier.width(120.dp),
                )
            } else {
                PosterImage(
                    url = item.posterUrl,
                    contentDescription = item.title,
                    modifier = Modifier.width(96.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    item.year?.let { add(it.toString()) }
                    add(typeLabel(item.type))
                    item.communityRating?.let { add("★ ${"%.1f".format(it)}") }
                }
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MediaHubLabel(text = hit.serverName)
            }
        }
    }
}

@Composable
private fun ServerStatusLines(state: GlobalSearchState) {
    MediaHubCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 进行中：统一一个 spinner 行（具体哪几台在跑对用户噪音过大，不逐台列名）
            if (state.isSearching) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "正在搜索更多媒体源…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.errors.forEach { (_, message) ->
                Text(
                    "部分来源失败：$message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun typeLabel(type: MediaType): String = when (type) {
    MediaType.MOVIE -> "电影"
    MediaType.SERIES -> "剧集"
    MediaType.SEASON -> "季"
    MediaType.EPISODE -> "单集"
    MediaType.VIDEO -> "视频"
    MediaType.AUDIO -> "音频"
    MediaType.FOLDER -> "文件夹"
    MediaType.OTHER -> "条目"
    else -> "条目"
}
