package com.akiha.akihalink.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The small set of non-semantic accents used throughout the product. */
object AkihaPalette {
    val Indigo = Color(0xFF5146E5)
    val IndigoLight = Color(0xFF746BFF)
    val Mint = Color(0xFF23C9A1)
    val MintDark = Color(0xFF063C34)
    val Amber = Color(0xFFF3AD4E)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF4438CA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E3FF),
    onPrimaryContainer = Color(0xFF21196F),
    secondary = Color(0xFF316B62),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0F3E9),
    onSecondaryContainer = Color(0xFF123E37),
    tertiary = Color(0xFF8A5A18),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2B9),
    onTertiaryContainer = Color(0xFF4C3107),
    background = Color(0xFFF7F7FB),
    onBackground = Color(0xFF19191F),
    surface = Color(0xFFF7F7FB),
    onSurface = Color(0xFF19191F),
    surfaceVariant = Color(0xFFE7E5EC),
    onSurfaceVariant = Color(0xFF62616B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0EFF5),
    surfaceContainer = Color(0xFFEAE9F0),
    surfaceContainerHigh = Color(0xFFE4E2EA),
    surfaceContainerHighest = Color(0xFFDCD9E3),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC9C6D1),
    error = Color(0xFFB4233A),
    onError = Color.White,
    errorContainer = Color(0xFFFFD9DE),
    onErrorContainer = Color(0xFF650017),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC5C0FF),
    onPrimary = Color(0xFF241A91),
    primaryContainer = Color(0xFF37305F),
    onPrimaryContainer = Color(0xFFE6E3FF),
    secondary = Color(0xFF78DDC5),
    onSecondary = Color(0xFF00382E),
    secondaryContainer = Color(0xFF184C42),
    onSecondaryContainer = Color(0xFFB8F2E4),
    tertiary = Color(0xFFFFCA85),
    onTertiary = Color(0xFF4A2B00),
    tertiaryContainer = Color(0xFF583C19),
    onTertiaryContainer = Color(0xFFFFE2B9),
    background = Color(0xFF0D0E13),
    onBackground = Color(0xFFE8E7EE),
    surface = Color(0xFF0D0E13),
    onSurface = Color(0xFFE8E7EE),
    surfaceVariant = Color(0xFF45444D),
    onSurfaceVariant = Color(0xFFC8C5D0),
    surfaceContainerLowest = Color(0xFF08090D),
    surfaceContainerLow = Color(0xFF15161C),
    surfaceContainer = Color(0xFF1A1B22),
    surfaceContainerHigh = Color(0xFF22232B),
    surfaceContainerHighest = Color(0xFF2C2D36),
    outline = Color(0xFF908E99),
    outlineVariant = Color(0xFF3B3A44),
    error = Color(0xFFFFB2BC),
    onError = Color(0xFF680019),
    errorContainer = Color(0xFF8F1730),
    onErrorContainer = Color(0xFFFFD9DE),
)

private val AkihaTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-1.5).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.7).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
)

private val AkihaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

object AkihaSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val page = 20.dp
    val section = 28.dp
    val xl = 40.dp
    val xxl = 56.dp
}

object AkihaMotion {
    const val Fast = 150
    const val Default = 280
    const val Slow = 480
}

@Composable
fun AkihaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AkihaTypography,
        shapes = AkihaShapes,
        content = content,
    )
}
