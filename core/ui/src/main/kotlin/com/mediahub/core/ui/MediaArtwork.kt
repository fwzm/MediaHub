package com.mediahub.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * 媒体图片组件（Phase 1B-2.3 Artwork Pipeline）。
 *
 * URL 由 Provider 层生成（无 Token，ADR-026）；鉴权头由全局 ImageLoader 的
 * EmbyImageAuthInterceptor 注入。统一占位/错误态（灰底 + 图标），Coil 按
 * composable 尺寸下采样 + 磁盘/内存缓存。
 */

/** 竖版海报（电影/剧集/季），2:3。 */
@Composable
fun PosterImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    MediaArtwork(
        url = url,
        contentDescription = contentDescription,
        modifier = modifier.aspectRatio(POSTER_ASPECT),
    )
}

/** 横版缩略图（单集列表），16:9。 */
@Composable
fun ThumbImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    MediaArtwork(
        url = url,
        contentDescription = contentDescription,
        modifier = modifier.aspectRatio(THUMB_ASPECT),
    )
}

/** 详情页背景大图（fanart），16:9。 */
@Composable
fun BackdropImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    MediaArtwork(
        url = url,
        contentDescription = contentDescription,
        modifier = modifier.aspectRatio(THUMB_ASPECT),
    )
}

/** 带进度条的缩略图（继续观看卡片）。progress ∈ [0,1]。 */
@Composable
fun ThumbImageWithProgress(
    url: String?,
    contentDescription: String?,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.aspectRatio(THUMB_ASPECT)) {
        MediaArtwork(
            url = url,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.Black.copy(alpha = 0.35f)),
            trackColor = Color.White.copy(alpha = 0.3f),
        )
    }
}

@Composable
private fun MediaArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (url == null) {
            PlaceholderIcon()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { PlaceholderIcon() },
                error = { PlaceholderIcon(icon = Icons.Default.BrokenImage) },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PlaceholderIcon(icon: ImageVector = Icons.Default.Movie) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.align(Alignment.Center).fillMaxSize(0.4f),
    )
}

private val POSTER_ASPECT = 2f / 3f
private val THUMB_ASPECT = 16f / 9f
