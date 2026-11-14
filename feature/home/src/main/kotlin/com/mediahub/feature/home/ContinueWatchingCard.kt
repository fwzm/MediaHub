package com.mediahub.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediahub.core.ui.MediaHubCard
import com.mediahub.core.ui.MediaHubLabel
import com.mediahub.core.ui.ThumbImageWithProgress
import com.mediahub.model.PlaybackProgress

/** 首页与「查看全部」共用的继续观看卡片。 */
@Composable
internal fun ContinueWatchingCard(
    progress: PlaybackProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val remainingMinutes = ((progress.durationMs - progress.positionMs).coerceAtLeast(0L) / 60_000).toInt()
    MediaHubCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(10.dp),
    ) {
        Box {
            ThumbImageWithProgress(
                url = progress.posterUrl,
                contentDescription = progress.itemTitle,
                progress = fraction,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                        ),
                    )
                    .padding(start = 12.dp, top = 32.dp, end = 12.dp, bottom = 14.dp),
            ) {
                Text(
                    text = progress.itemTitle ?: "未命名",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaHubLabel(text = "已看 ${(fraction * 100).toInt()}%", emphasized = true)
                    if (remainingMinutes > 0) {
                        Text(
                            "剩余约 $remainingMinutes 分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
