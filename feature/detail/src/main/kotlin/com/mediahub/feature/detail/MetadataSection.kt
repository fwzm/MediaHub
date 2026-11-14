package com.mediahub.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediahub.metadata.match.MatchCandidate
import com.mediahub.metadata.match.MatchReason
import com.mediahub.metadata.match.MetadataMatch
import com.mediahub.metadata.ratings.PlatformRating
import java.util.Locale

/**
 * 手动匹配弹窗（条目 51 / 67）：只负责展示 [ManualMatchState] 与转发动作，
 * 搜索/打分全部由 ViewModel 调用上一轮的 ManualMatchService 完成。
 */
@Composable
fun ManualMatchDialog(
    state: ManualMatchState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (MatchCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动匹配") },
        text = {
            Column {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text("片名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.searching) {
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    }
                }
                state.error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.ambiguous) {
                    Text(
                        "存在多个接近的候选，请人工确认。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.candidates.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                    ) {
                        state.candidates.forEach { candidate ->
                            CandidateRow(candidate = candidate, onClick = { onSelect(candidate) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSearch, enabled = !state.searching) { Text("搜索") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CandidateRow(candidate: MatchCandidate, onClick: () -> Unit) {
    val result = candidate.result
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row {
            Text(
                result.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text("${candidate.score} 分", style = MaterialTheme.typography.labelMedium)
        }
        val meta = buildList {
            result.year?.let { add(it.toString()) }
            add(result.source.label)
            if (candidate.confident) add("可自动采用")
        }
        Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
        if (candidate.reasons.isNotEmpty()) {
            Text(
                candidate.reasons.joinToString("、") { it.displayLabel() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 已确认的匹配摘要（无匹配时整行不渲染）。 */
@Composable
fun MetadataMatchSummary(match: MetadataMatch, modifier: Modifier = Modifier) {
    val yearSuffix = match.year?.let { " ($it)" } ?: ""
    Text(
        "刮削：${match.source.label} · ${match.title}$yearSuffix",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * 多平台评分并排（条目 115）。
 *
 * 只渲染 [ratings] 中实际返回数据的平台 —— 调用方传入空列表或缺失平台时该平台不占位。
 * 各平台保留原生量纲展示，另附统一 10 分制的「综合」值（≥2 个平台时）。
 */
@Composable
fun RatingsStrip(
    ratings: List<PlatformRating>,
    average: Double?,
    modifier: Modifier = Modifier,
) {
    if (ratings.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ratings.forEach { rating ->
            Column {
                Text(
                    rating.platform.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(rating.display, style = MaterialTheme.typography.titleSmall)
            }
        }
        if (average != null && ratings.size >= 2) {
            Column {
                Text(
                    "综合",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    String.format(Locale.US, "%.1f", average),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

private fun MatchReason.displayLabel(): String = when (this) {
    MatchReason.TITLE_EXACT -> "标题精确"
    MatchReason.ORIGINAL_TITLE_EXACT -> "原名精确"
    MatchReason.TITLE_CONTAINS -> "标题包含"
    MatchReason.TITLE_SIMILAR -> "标题相近"
    MatchReason.YEAR_EXACT -> "年份一致"
    MatchReason.YEAR_NEAR -> "年份接近"
    MatchReason.YEAR_MISMATCH -> "年份不符"
    MatchReason.LANGUAGE_MATCH -> "语言匹配"
    MatchReason.LANGUAGE_MISMATCH -> "语言不符"
}
