package com.mediahub.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MediaHub 设计 token（可被 `:core:ui` 与各 feature 模块复用）。
 *
 * 收敛原则：先用最小 token 集覆盖现状，不追求完整设计系统。
 * - 间距以 4dp 为基准；页面横向 gutter 唯一值 = [MediaHubSpacing.l]（16dp）。
 * - 字号在 M3 默认值之上补全 bodySmall / labelSmall / labelMedium，避免落到阶外默认值。
 * - success / warning 不是 M3 角色槽位，用语义色 + CompositionLocal 补齐。
 */

/** 间距阶（4dp 基准）。 */
object MediaHubSpacing {
    /** 2dp：图标与文字内缝隙。 */
    val xxs = 2.dp

    /** 4dp：chip 内、紧贴元素。 */
    val xs = 4.dp

    /** 8dp：行内元素、卡片内小间隔。 */
    val s = 8.dp

    /** 12dp：列表项纵向节奏。 */
    val m = 12.dp

    /** 16dp：页面横向 gutter 唯一值、卡片内边距。 */
    val l = 16.dp

    /** 24dp：分组之间。 */
    val xl = 24.dp

    /** 32dp：页面区块之间。 */
    val xxl = 32.dp
}

/** 动效时长（毫秒），供 `tween()` / `animate*AsState` 复用。 */
object MediaHubMotion {
    const val Fast = 120
    const val Base = 200
    const val Slow = 300
}

/**
 * M3 没有 success / warning 角色，这里补两个语义色及其 on 色。
 *
 * 深色模式下对比度（WCAG 2.1，背景 #0A0D14）：
 * - success #7BD88F ≈ 11.2:1（AAA）
 * - onSuccess #052E16 压在 success 上 ≈ 8.6:1（AAA）
 * - warning #FFCE6B ≈ 13.2:1（AAA）
 * - onWarning #3D2A00 压在 warning 上 ≈ 9.3:1（AAA）
 */
data class MediaHubSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
)

val DarkMediaHubSemanticColors = MediaHubSemanticColors(
    success = Color(0xFF7BD88F),
    onSuccess = Color(0xFF052E16),
    warning = Color(0xFFFFCE6B),
    onWarning = Color(0xFF3D2A00),
)

val LocalMediaHubSemanticColors = staticCompositionLocalOf { DarkMediaHubSemanticColors }

/** 用法：`MaterialTheme.mediaHubSemanticColors.success`。 */
val MaterialTheme.mediaHubSemanticColors: MediaHubSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMediaHubSemanticColors.current

private val MediaHubDarkColorScheme = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF0B1D42),
    primaryContainer = Color(0xFF263A68),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFB9C7E6),
    onSecondary = Color(0xFF172238),
    secondaryContainer = Color(0xFF28344B),
    onSecondaryContainer = Color(0xFFD6E2FA),
    tertiary = Color(0xFF8FE3D2),
    onTertiary = Color(0xFF00372E),
    background = Color(0xFF0A0D14),
    onBackground = Color(0xFFE4E8F2),
    surface = Color(0xFF0A0D14),
    onSurface = Color(0xFFE4E8F2),
    surfaceDim = Color(0xFF0A0D14),
    surfaceBright = Color(0xFF2A303D),
    surfaceContainerLowest = Color(0xFF080B11),
    surfaceContainerLow = Color(0xFF111620),
    surfaceContainer = Color(0xFF161C28),
    surfaceContainerHigh = Color(0xFF1D2432),
    surfaceContainerHighest = Color(0xFF252D3C),
    surfaceVariant = Color(0xFF252D3C),
    onSurfaceVariant = Color(0xFFAFB9CE),
    outline = Color(0xFF46536C),
    outlineVariant = Color(0xFF2B3548),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** 深色主题（当前唯一主题）。 */
fun mediaHubDarkColorScheme(): ColorScheme = MediaHubDarkColorScheme

private val MediaHubTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp),
)

/** 字号阶：在现状 8 个覆写基础上补齐 bodySmall / labelSmall / labelMedium。 */
fun mediaHubTypography(): Typography = MediaHubTypography

private val MediaHubShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

fun mediaHubShapes(): Shapes = MediaHubShapes

/**
 * 主题实现放在 `:core:ui`，供各 feature 模块直接复用；
 * `:app` 的 `MediaHubTheme` 仅作兼容委托。
 */
@Composable
fun MediaHubTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMediaHubSemanticColors provides DarkMediaHubSemanticColors) {
        MaterialTheme(
            colorScheme = MediaHubDarkColorScheme,
            typography = MediaHubTypography,
            shapes = MediaHubShapes,
            content = content,
        )
    }
}
