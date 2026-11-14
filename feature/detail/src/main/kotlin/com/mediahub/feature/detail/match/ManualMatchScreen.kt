package com.mediahub.feature.detail.match

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.ThumbImage
import com.mediahub.model.MediaItem

/**
 * 手动匹配独立页面（条目 129 / 133）。
 *
 * - 129：整页不用自适应高度动画；候选行高度取自 [MATCH_RESULT_ROW_HEIGHT_DP]，
 *   状态区与结果区共用同一容器，输入、加载、出错都不会让页面上下抽动。
 * - 133：所有颜色来自 `MaterialTheme.colorScheme`，应用切到任何配色（含暗色）都自动适配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualMatchRoute(
    title: String,
    onBack: () -> Unit,
    onMatched: (MediaItem) -> Unit,
    viewModel: ManualMatchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text("手动匹配", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(
                    "正在为以下条目匹配",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = title.ifBlank { "未命名条目" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                singleLine = true,
                label = { Text("搜索片名") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                },
            )

            // 固定 3dp 的加载槽位：出现/消失不改变页面高度。
            Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
                if (state is ManualMatchState.Loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (val current = state) {
                    ManualMatchState.Idle -> MatchHint(matchQueryHint(query).orEmpty())
                    ManualMatchState.Loading -> Unit
                    is ManualMatchState.Error -> MatchError(
                        message = current.message,
                        onRetry = viewModel::retry,
                    )
                    is ManualMatchState.Results -> MatchResults(
                        candidates = current.candidates,
                        onUse = onMatched,
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchHint(text: String) {
    if (text.isBlank()) return
    Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MatchError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun MatchResults(candidates: List<MediaItem>, onUse: (MediaItem) -> Unit) {
    if (candidates.isEmpty()) {
        MatchHint("没有找到候选条目，换个片名再试")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MATCH_LIST_BOTTOM_PADDING_DP.dp),
    ) {
        item(key = "count", contentType = "count") {
            Text(
                text = "找到 ${candidates.size} 条候选",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(candidates, key = { it.id }, contentType = { "candidate" }) { candidate ->
            CandidateRow(candidate = candidate, onUse = { onUse(candidate) })
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun CandidateRow(candidate: MediaItem, onUse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MATCH_RESULT_ROW_HEIGHT_DP.dp)
            .clickable(onClick = onUse)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThumbImage(
            url = candidate.posterUrl ?: candidate.backdropUrl,
            contentDescription = candidate.title,
            modifier = Modifier.height(52.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = MATCH_RESULT_TITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = matchCandidateLine(candidate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "使用",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
