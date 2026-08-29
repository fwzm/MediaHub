package com.mediahub.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.PosterImage
import com.mediahub.core.ui.ThumbImage
import com.mediahub.feature.search.engine.GlobalSearchState
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
    val input by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = input,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text("搜索电影 / 剧集 / 单集") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (input.isNotEmpty()) {
                                IconButton(onClick = {
                                    viewModel.onQueryChange("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "清空")
                                }
                            }
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
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
private fun SearchBody(
    state: GlobalSearchState,
    onOpenItem: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.query.isBlank()) {
        Column(
            modifier = modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "输入关键词，聚合搜索所有媒体源",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp, vertical = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.hits, key = { "${it.item.serverId}/${it.item.id}" }) { hit ->
            SearchHitRow(hit = hit, onClick = { onOpenItem(hit.item) })
        }

        // 逐服务器状态行：进行中 / 失败（partial success 不打断结果流）
        if (state.isSearching || state.errors.isNotEmpty()) {
            item { ServerStatusLines(state) }
        }

        if (!state.isSearching && state.hits.isEmpty() && state.errors.isEmpty()) {
            item {
                Text(
                    "没有找到与「${state.query}」匹配的内容",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: UnifiedSearchHit, onClick: () -> Unit) {
    val item = hit.item
    val isThumbShape = item.type == MediaType.EPISODE || item.type == MediaType.VIDEO
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
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
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
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
            Text(
                hit.serverName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ServerStatusLines(state: GlobalSearchState) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        // 进行中：统一一个 spinner 行（具体哪几台在跑对用户噪音过大，不逐台列名）
        if (state.isSearching) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.width(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
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
                modifier = Modifier.padding(top = 4.dp),
            )
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
