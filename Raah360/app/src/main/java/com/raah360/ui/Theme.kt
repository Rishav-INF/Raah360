package com.raah360.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object R360 {
    val Bg = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val Surface2 = Color(0xFF1F252E)
    val Line = Color(0xFF2C3440)
    val Text = Color(0xFFF2F4F7)
    val Muted = Color(0xFF9AA4B2)
    val Go = Color(0xFF22C55E)
    val GoDim = Color(0xFF14532D)
    val Warn = Color(0xFFF5A524)
    val Bad = Color(0xFFEF4444)
    val Info = Color(0xFF60A5FA)
}

private val scheme = darkColorScheme(
    primary = R360.Go,
    onPrimary = Color(0xFF04210F),
    secondary = R360.Info,
    background = R360.Bg,
    onBackground = R360.Text,
    surface = R360.Surface,
    onSurface = R360.Text,
    surfaceVariant = R360.Surface2,
    onSurfaceVariant = R360.Muted,
    outline = R360.Line,
    error = R360.Bad,
)

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun Raah360Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
