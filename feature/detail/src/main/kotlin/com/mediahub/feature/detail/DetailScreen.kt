package com.mediahub.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.BackdropImage
import com.mediahub.core.ui.PosterImage
import com.mediahub.model.MediaDetail
import com.mediahub.model.PlaybackLaunchSnapshot

/**
 * 详情页（Phase 1B-2.3 极简版）：backdrop + 海报 + 元信息 + 简介 + 播放入口。
 * 季/集导航仍走库浏览；演职人员/版本选择不在本期范围（见 TASKS.md）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailRoute(
    title: String,
    onBack: () -> Unit,
    onPlay: (serverId: String, itemId: String, snapshot: PlaybackLaunchSnapshot) -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
            )
        }
    }
}

@Composable
private fun DetailBody(
    detail: MediaDetail,
    modifier: Modifier = Modifier,
    onPlay: (com.mediahub.model.MediaItem) -> Unit,
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
