package com.mediahub.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil.compose.SubcomposeAsyncImage
import java.io.File

/**
 * 服务器图标（统一组件：首页卡片 / 媒体源页 / 播放器 Overlay 共用）。
 *
 * [icon] 约定（Server Editor 定义）：
 * - null → 默认（Provider 首字母徽标）
 * - builtin://&lt;type&gt; → 内置 Provider 图标（暂以首字母徽标呈现，后续可替换真实 Logo）
 * - file://&lt;abs-path&gt; → 自定义图片（应用私有目录，见 ServerIconStore）
 */
@Composable
fun ServerIcon(
    icon: String?,
    label: String,
    modifier: Modifier = Modifier,
) {
    val model = serverIconModel(icon)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { LetterBadge(label) },
                error = { LetterBadge(label) },
            )
        } else {
            LetterBadge(label)
        }
    }
}

/** 把 icon 引用转成 Coil model：file:// → File，builtin:// → null（回退徽标），其余当作 URL。 */
fun serverIconModel(icon: String?): Any? = when {
    icon.isNullOrBlank() -> null
    icon.startsWith("file://") -> File(icon.removePrefix("file://"))
    icon.startsWith("builtin://") -> null
    else -> icon
}

@Composable
private fun LetterBadge(label: String) {
    Text(
        text = label.firstOrNull()?.uppercase() ?: "?",
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
}
