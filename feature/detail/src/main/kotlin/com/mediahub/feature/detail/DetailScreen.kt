package com.mediahub.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.BackdropImage
import com.mediahub.core.ui.PosterImage
import com.mediahub.core.ui.ThumbImage
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackLaunchSnapshot
import kotlin.math.roundToInt

/**
 * 详情页（Phase 1B-3.1）：backdrop + 海报 + 元信息 + 简介 + 播放入口 + 演员/导演 + 制作公司。
 * 对 SERIES 类型额外展示季/集选择器（复用 browse 链，不用专用 getSeasons/getEpisodes）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailRoute(
    title: String,
    onBack: () -> Unit,
    onPlay: (serverId: String, itemId: String, snapshot: PlaybackLaunchSnapshot) -> Unit,
    onOpenItem: ((MediaItem) -> Unit)? = null,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val seriesState by viewModel.seriesState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val displayTitle = (state as? DetailUiState.Content)?.detail?.item?.title ?: title
                    Text(displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            DetailUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is DetailUiState.Error -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::load) { Text("重试") }
                }
            }

            is DetailUiState.Content -> DetailBody(
                detail = s.detail,
                seriesState = seriesState,
                modifier = Modifier.fillMaxSize().padding(padding),
                onPlay = { item ->
                    onPlay(
                        viewModel.serverId,
                        viewModel.itemId,
                        PlaybackLaunchSnapshot(
                            itemId = item.id,
                            type = item.type,
                            title = item.title,
                            runtimeMs = item.runtimeMs,
                            posterUrl = item.posterUrl,
                            container = item.container,
                        )
                    )
                },
                onSelectSeason = viewModel::selectSeason,
                onRetryEpisodes = viewModel::retryEpisodes,
                onOpenItem = onOpenItem,
            )
        }
    }
}

@Composable
private fun DetailBody(
    detail: MediaDetail,
    seriesState: SeriesBrowseState,
    modifier: Modifier = Modifier,
    onPlay: (MediaItem) -> Unit,
    onSelectSeason: (String) -> Unit = {},
    onRetryEpisodes: () -> Unit = {},
    onOpenItem: ((MediaItem) -> Unit)? = null,
) {
    val item = detail.item
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // 顶部 backdrop，底部渐变过渡到内容区
        Box(modifier = Modifier.fillMaxWidth()) {
            BackdropImage(
                url = item.backdropUrl ?: item.posterUrl,
                contentDescription = "${item.title} 背景图",
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)),
                    ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PosterImage(
                url = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier.width(110.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    item.year?.let { add(it.toString()) }
                    item.runtimeMs?.let { ms ->
                        val minutes = ms / 60_000
                        if (minutes > 0) add("${minutes}分钟")
                    }
                    item.communityRating?.let { add("★ %.1f".format(it)) }
                    item.genres.take(3).let { if (it.isNotEmpty()) add(it.joinToString(" / ")) }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item.container?.uppercase()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        Button(
            onClick = { onPlay(item) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("播放")
        }

        // 导演
        val directors = item.people.filter { it.role == com.mediahub.model.Person.Role.DIRECTOR }
        if (directors.isNotEmpty()) {
            Text(
                "导演：${directors.joinToString("、") { it.name }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // 演员表（只显示 ACTOR）
        val cast = item.people.filter { it.role == com.mediahub.model.Person.Role.ACTOR }
        if (cast.isNotEmpty()) {
            Text(
                "演员",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(cast, key = { i, p -> p.id ?: "i" + i.toString() + ":" + p.role + ":" + p.name + ":" + (p.characterName ?: "") }) { _, person ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(72.dp),
                    ) {
                        PosterImage(
                            url = person.imageUrl,
                            contentDescription = person.name,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(50)),
                        )
                        Text(
                            person.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        person.characterName?.let { cn ->
                            Text(
                                cn,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 制作公司
        val studioText = item.studios.joinToString(" · ")
        if (studioText.isNotBlank()) {
            Text(
                studioText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // ---- Series 季/集浏览（1B-3.1） ----
        if (item.type == MediaType.SERIES) {
            Spacer(Modifier.height(16.dp))
            Text(
                "季",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (seriesState.libraryUnavailable) {
                Text(
                    "该数据源暂不支持剧集浏览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else if (seriesState.seasonsLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 16.dp).size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else if (seriesState.seasonsError != null) {
                Text(
                    seriesState.seasonsError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else if (seriesState.seasons.isEmpty()) {
                Text(
                    "暂无季信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(seriesState.seasons, key = { it.id }) { season ->
                        val seasonLabel = season.seasonNumber?.let { n ->
                            if (n == 0) "特别篇" else "第${n}季"
                        } ?: season.title
                        val selected = season.id == seriesState.selectedSeasonId
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectSeason(season.id) },
                            label = { Text(seasonLabel) },
                        )
                    }
                }
            }

            // Episode 列表
            if (seriesState.selectedSeasonId != null) {
                Spacer(Modifier.height(8.dp))
                if (seriesState.episodesLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(horizontal = 16.dp).size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (seriesState.episodesError != null) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            seriesState.episodesError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetryEpisodes) { Text("重试") }
                    }
                } else {
                    seriesState.episodes.forEach { ep ->
                        EpisodeRow(
                            episode = ep,
                            onClick = { onOpenItem?.invoke(ep) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(24.dp))

        item.overview?.takeIf(String::isNotBlank)?.let { overview ->
            var expanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    overview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!expanded && overview.length > 120) {
                    TextButton(onClick = { expanded = true }) { Text("展开") }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: MediaItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThumbImage(
            url = episode.posterUrl,
            contentDescription = episode.title,
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            val epLabel = buildString {
                episode.seasonNumber?.let { append("S").append(it.toString().padStart(2, '0')) }
                episode.episodeNumber?.let { append("E").append(it.toString().padStart(2, '0')) }
                if (isNotEmpty()) append("  ")
                append(episode.title)
            }
            Text(
                epLabel,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                episode.runtimeMs?.let { ms ->
                    val minutes = ms / 60_000
                    if (minutes > 0) Text(
                        "${minutes}分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                episode.userData?.playedPercentage?.let { pct ->
                    when {
                        pct >= 95.0 || episode.playCount > 0 -> {
                            Text("已看", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        pct > 0 -> {
                            Text("进度 " + pct.roundToInt().toString() + "%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            episode.overview?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}