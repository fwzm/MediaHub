package com.mediahub.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 深色主色板（媒体内容优先、高对比度、克制）。 */
private val MediaHubColorScheme = darkColorScheme(
    primary = Color(0xFF8FA9FF),
    onPrimary = Color(0xFF10141F),
    primaryContainer = Color(0xFF2A3350),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF9FB0D8),
    onSecondary = Color(0xFF131B2E),
    background = Color(0xFF0F1014),
    onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF16181F),
    onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF20232C),
    onSurfaceVariant = Color(0xFFB8BCC9),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0A0A),
)

@Composable
fun MediaHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MediaHubColorScheme,
        typography = Typography(),
        content = content,
    )
}
