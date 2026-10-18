package com.atomkarnickel.aviationoptimizer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Navy900 = Color(0xFF0B1A33)
val Navy800 = Color(0xFF11264A)
val Navy700 = Color(0xFF1A3561)
val AviationBlue = Color(0xFF2F7DE1)
val AviationBlueLight = Color(0xFF4F97F0)
val Teal = Color(0xFF22C3B5)
val TealDark = Color(0xFF0F8F84)
val Demo = Color(0xFF7A3FB0)

private val Light = lightColorScheme(
    primary = AviationBlue,
    onPrimary = Color.White,
    secondary = TealDark,
    onSecondary = Color.White,
    tertiary = Navy800,
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F1F38),
    surface = Color.White,
    onSurface = Color(0xFF0F1F38),
    surfaceVariant = Color(0xFFEEF3F9),
    onSurfaceVariant = Color(0xFF4F6380),
    outline = Color(0xFFD6E0EC),
    error = Color(0xFFC0392B),
    errorContainer = Color(0xFFFDECEA),
)

private val Dark = darkColorScheme(
    primary = AviationBlueLight,
    onPrimary = Color(0xFF061024),
    secondary = Color(0xFF5AD8CC),
    onSecondary = Color(0xFF061024),
    tertiary = Color(0xFF9FB3D1),
    background = Color(0xFF081326),
    onBackground = Color(0xFFE6EEFB),
    surface = Color(0xFF0F1F3A),
    onSurface = Color(0xFFE6EEFB),
    surfaceVariant = Color(0xFF142A4D),
    onSurfaceVariant = Color(0xFF9FB3D1),
    outline = Color(0xFF23406C),
    error = Color(0xFFFF8A7A),
    errorContainer = Color(0xFF3A1714),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AviationTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
