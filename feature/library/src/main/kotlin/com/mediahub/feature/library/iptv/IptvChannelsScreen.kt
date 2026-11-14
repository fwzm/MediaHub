package com.mediahub.feature.library.iptv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.ThumbImage
import com.mediahub.model.MediaItem

@Composable
fun IptvChannelsRoute(
    onBack: () -> Unit,
    onOpenChannel: (MediaItem) -> Unit,
    viewModel: IptvChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    IptvChannelsScreen(
        state = state,
        title = viewModel.serverName.ifBlank { "IPTV" },
        onBack = onBack,
        onRetry = viewModel::retry,
        onSelectGroup = viewModel::selectGroup,
        onQueryChange = viewModel::setQuery,
        onToggleFavoritesOnly = viewModel::toggleFavoritesOnly,
        onToggleFavorite = viewModel::toggleFavorite,
        onOpenChannel = onOpenChannel,
        onLoadMore = viewModel::loadMore,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvChannelsScreen(
    state: IptvChannelsUiState,
    title: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelectGroup: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onOpenChannel: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val content = state as? IptvChannelsUiState.Content
                    IconButton(onClick = onToggleFavoritesOnly, enabled = content != null) {
                        Icon(
                            imageVector = if (content?.favoritesOnly == true) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Outlined.FavoriteBorder
                            },
                            contentDescription = if (content?.favoritesOnly == true) "显示全部" else "只显示收藏",
                            tint = if (content?.favoritesOnly == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                is IptvChannelsUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                is IptvChannelsUiState.Error -> ErrorState(message = state.message, onRetry = onRetry)

                is IptvChannelsUiState.Content -> ChannelContent(
                    state = state,
                    onSelectGroup = onSelectGroup,
                    onQueryChange = onQueryChange,
                    onToggleFavorite = onToggleFavorite,
                    onOpenChannel = onOpenChannel,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun ChannelContent(
    state: IptvChannelsUiState.Content,
    onSelectGroup: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onOpenChannel: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text("搜索频道") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.groups, key = { it.id }) { group ->
                FilterChip(
                    selected = group.id == state.selectedGroupId,
                    onClick = { onSelectGroup(group.id) },
                    label = {
                        Text(
                            if (group.itemCount != null) "${group.name} (${group.itemCount})" else group.name,
                        )
                    },
                )
            }
        }
        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        state.favoritesOnly -> "还没有收藏的频道"
                        state.query.isNotBlank() -> "没有匹配的频道"
                        else -> "该分组没有频道"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.items, key = { it.id }) { item ->
                ChannelRow(
                    item = item,
                    isFavorite = item.id in state.favorites,
                    onOpen = { onOpenChannel(item) },
                    onToggleFavorite = { onToggleFavorite(item) },
                )
            }
            item(key = "footer") {
                LoadMoreFooter(state = state, onLoadMore = onLoadMore)
            }
        }
    }
}

@Composable
private fun ChannelRow(
    item: MediaItem,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbImage(
            url = item.posterUrl,
            contentDescription = item.title,
            modifier = Modifier.size(width = 88.dp, height = 50.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.libraryId?.takeIf { it.isNotBlank() }?.let { group ->
                Text(
                    text = group,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun LoadMoreFooter(state: IptvChannelsUiState.Content, onLoadMore: () -> Unit) {
    when {
        state.isLoadingMore -> Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }

        state.loadMoreError != null -> Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = onLoadMore) { Text("加载失败，点击重试") }
        }

        state.hasMore -> LaunchedEffect(state.loadedCount, state.hasMore) { onLoadMore() }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("重试") }
    }
}
