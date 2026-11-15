package com.mediahub.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.model.WatchSession
import com.mediahub.model.WatchStatsOverview
import com.mediahub.model.WatchTrendPoint
import java.time.YearMonth
import java.util.Locale

/**
 * 观影统计页（条目 141）：总览 / 回顾 / 观影日历 / 趋势 / 历史。
 *
 * 条目 122：开关关闭时页面顶部明确提示"已停止记录，已有记录保留"；
 * 入口由设置页按开关隐藏，正常路径下不会进入本页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsRoute(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatsScreen(state = state, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    state: StatsUiState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("观影统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val overview = state.overview
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.enabled) {
                item {
                    NoticeCard("观影统计已关闭：不再记录新的观看，已有记录保留。")
                }
            }
            if (overview.isEmpty) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("还没有观看记录", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                item { SummaryCards(overview) }
                item { ReviewCard(overview) }
                item { CalendarSection(overview) }
                item { TrendSection(overview.trend) }
                item {
                    Text(
                        "观看历史",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(overview.history) { session -> HistoryRow(session) }
                item { Box(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SummaryCards(overview: WatchStatsOverview) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("总时长", formatWatchDuration(overview.totalWatchedMs), Modifier.weight(1f))
            MetricCard("观看天数", "${overview.watchDayCount} 天", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("电影", "${overview.movieCount} 部", Modifier.weight(1f))
            MetricCard("剧集", "${overview.episodeCount} 集", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ReviewCard(overview: WatchStatsOverview) {
    val review = overview.review
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("观影回顾", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "共观看 ${review.sessionCount} 次，连续观看最长 ${review.longestStreakDays} 天。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            review.topTitle?.let { title ->
                Text(
                    "看得最多：$title（${formatWatchDuration(review.topTitleWatchedMs)}）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CalendarSection(overview: WatchStatsOverview) {
    var monthOffset by remember { mutableIntStateOf(0) }
    val month = YearMonth.now().plusMonths(monthOffset.toLong())
    val calendar = buildStatsCalendarMonth(
        year = month.year,
        month = month.monthValue,
        watchByDay = watchByDayOf(overview.calendar),
    )
    val maxMs = calendar.cells.maxOfOrNull { it?.watchedMs ?: 0L }?.coerceAtLeast(1L) ?: 1L

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "观影日历",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextAction("上个月") { monthOffset -= 1 }
            Text(
                "%d-%02d".format(Locale.ROOT, calendar.year, calendar.month),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            TextAction("下个月") { monthOffset += 1 }
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                Text(
                    day,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        calendar.cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .height(36.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(calendarCellColor(cell?.watchedMs ?: 0L, maxMs)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cell != null) {
                            Text(
                                cell.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun calendarCellColor(watchedMs: Long, maxMs: Long): Color {
    if (watchedMs <= 0) return MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val ratio = (watchedMs.toFloat() / maxMs).coerceIn(0.15f, 1f)
    return MaterialTheme.colorScheme.primary.copy(alpha = ratio)
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TrendSection(trend: List<WatchTrendPoint>) {
    val maxMs = trend.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1L) ?: 1L
    Column {
        Text(
            "近 30 天趋势",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(72.dp),
        ) {
            if (trend.isEmpty()) return@Canvas
            val gap = 2.dp.toPx()
            val barWidth = ((size.width - gap * (trend.size - 1)) / trend.size).coerceAtLeast(1f)
            trend.forEachIndexed { index, point ->
                val ratio = point.watchedMs.toFloat() / maxMs
                val barHeight = (size.height * ratio).coerceAtLeast(if (point.watchedMs > 0) 2.dp.toPx() else 0f)
                drawRect(
                    color = Color(0xFF7C4DFF),
                    topLeft = androidx.compose.ui.geometry.Offset(
                        x = index * (barWidth + gap),
                        y = size.height - barHeight,
                    ),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(session: WatchSession) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                session.title.ifBlank { session.itemId },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatWatchDuration(session.watchedMs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(modifier = Modifier.padding(top = 2.dp)) {
            Text(
                formatWatchDate(session.endedAtEpochMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (session.completed) {
                Text(
                    "已看完",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
