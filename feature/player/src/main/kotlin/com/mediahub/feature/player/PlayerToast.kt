package com.mediahub.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 审计 P6：播放页提示条的统一视觉契约。
 *
 * 三个提示条（引擎切换 / 播放失败 / 音频不支持）此前各自手写背景、圆角和文字色，
 * 出现黑 0.65 直角、黑 0.55 直角和 Color.Yellow 三套样式。本组件把「语气 → 语义色」
 * 收敛到一处：所有提示共用 12dp 圆角，INFO 用中性半透明黑，WARNING 用琥珀色，
 * ERROR 用红色；调用方只传文案与语气，不再直接写 Color。
 */
internal enum class PlayerToastTone { INFO, WARNING, ERROR }

internal data class PlayerToastStyle(
    val background: Color,
    val foreground: Color,
    val cornerRadiusDp: Float,
)

internal fun playerToastStyle(tone: PlayerToastTone): PlayerToastStyle = when (tone) {
    PlayerToastTone.INFO -> PlayerToastStyle(
        background = Color.Black.copy(alpha = 0.72f),
        foreground = Color.White,
        cornerRadiusDp = 12f,
    )

    PlayerToastTone.WARNING -> PlayerToastStyle(
        background = Color(0xFF4A3B00).copy(alpha = 0.92f),
        foreground = Color(0xFFFFE082),
        cornerRadiusDp = 12f,
    )

    PlayerToastTone.ERROR -> PlayerToastStyle(
        background = Color(0xFF5C1A1A).copy(alpha = 0.92f),
        foreground = Color(0xFFFFB4AB),
        cornerRadiusDp = 12f,
    )
}

@Composable
internal fun PlayerToast(
    text: String,
    tone: PlayerToastTone,
    modifier: Modifier = Modifier,
) {
    val style = playerToastStyle(tone)
    Text(
        text = text,
        color = style.foreground,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .background(style.background, RoundedCornerShape(style.cornerRadiusDp.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
