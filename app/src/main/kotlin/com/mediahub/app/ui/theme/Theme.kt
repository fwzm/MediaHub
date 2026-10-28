package com.mediahub.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val MediaHubColorScheme = darkColorScheme(
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

private val MediaHubTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

private val MediaHubShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun MediaHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MediaHubColorScheme,
        typography = MediaHubTypography,
        shapes = MediaHubShapes,
        content = content,
    )
}
