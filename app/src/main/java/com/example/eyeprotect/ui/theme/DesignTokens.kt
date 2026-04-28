package com.example.eyeprotect.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class EyeColorTokens(
    val background: Color,
    val backgroundElevated: Color,
    val surface: Color,
    val cardContainer: Color,
    val surfaceSubtle: Color,
    val surfaceInverse: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnInverse: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val navBackground: Color,
    val navActiveContainer: Color,
    val navActiveIcon: Color,
    val navInactiveIcon: Color,
    val headerGradientStart: Color,
    val headerGradientEnd: Color,
    val metricDistanceBg: Color,
    val metricDistanceFg: Color,
    val metricBlinkBg: Color,
    val metricBlinkFg: Color,
    val metricPostureBg: Color,
    val metricPostureFg: Color,
    val metricLyingBg: Color,
    val metricLyingFg: Color,
)

@Immutable
data class EyeTypographyTokens(
    val heroTitle: TextStyle,
    val pageTitle: TextStyle,
    val sectionTitle: TextStyle,
    val cardTitle: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
)

@Immutable
data class EyeSpacingTokens(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val x2: Dp = 28.dp,
    val x3: Dp = 32.dp,
)

@Immutable
data class EyeRadiusTokens(
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 22.dp,
    val xl: Dp = 28.dp,
    val pill: Dp = 999.dp,
)

@Immutable
data class EyeElevationTokens(
    val none: Dp = 0.dp,
    val low: Dp = 1.dp,
    val medium: Dp = 3.dp,
    val high: Dp = 6.dp,
)

private val LightEyeColorTokens = EyeColorTokens(
    background = Color(0xFFF2F2F7),
    backgroundElevated = Color(0xFFF9F9F9),
    surface = Color(0xFFFFFFFF),
    cardContainer = Color(0xFFFFFFFF),
    surfaceSubtle = Color(0xFFF9F9F9),
    surfaceInverse = Color(0xFF1C1C1E),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFFC7C7CC),
    textOnInverse = Color(0xFFFFFFFF),
    borderSubtle = Color(0xFFE5E5EA),
    borderStrong = Color(0xFFE5E5EA),
    accentPrimary = Color(0xFF1C1C1E),
    accentSecondary = Color(0xFFE97A38),
    success = Color(0xFF4B8B5C),
    warning = Color(0xFFF2B84B),
    error = Color(0xFFE45A5A),
    navBackground = Color(0xFF1C1C1E),
    navActiveContainer = Color(0xFFFFFFFF),
    navActiveIcon = Color(0xFF1C1C1E),
    navInactiveIcon = Color(0xFFFFFFFF).copy(alpha = 0.62f),
    headerGradientStart = Color(0xFFF2F2F7),
    headerGradientEnd = Color(0xFFF2F2F7),
    metricDistanceBg = Color(0xFFFFDDD0),
    metricDistanceFg = Color(0xFFE97A38),
    metricBlinkBg = Color(0xFFD4EDD4),
    metricBlinkFg = Color(0xFF4B8B5C),
    metricPostureBg = Color(0xFFE8E0F5),
    metricPostureFg = Color(0xFF7A58A8),
    metricLyingBg = Color(0xFFFFD6D6),
    metricLyingFg = Color(0xFFE06C9A),
)

private val DarkEyeColorTokens = EyeColorTokens(
    background = Color(0xFF111111),
    backgroundElevated = Color(0xFF2C2C2E),
    surface = Color(0xFF1C1C1E),
    cardContainer = Color(0xFF1C1C1E),
    surfaceSubtle = Color(0xFF2C2C2E),
    surfaceInverse = Color(0xFFFFFFFF),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFF48484A),
    textOnInverse = Color(0xFF1A1A1A),
    borderSubtle = Color(0xFF3A3A3C),
    borderStrong = Color(0xFF3A3A3C),
    accentPrimary = Color(0xFFFF8C69),
    accentSecondary = Color(0xFFFF8C69),
    success = Color(0xFF8ED39C),
    warning = Color(0xFFFF8C69),
    error = Color(0xFFFFA5A5),
    navBackground = Color(0xFF1C1C1E),
    navActiveContainer = Color(0xFFFFFFFF),
    navActiveIcon = Color(0xFF1A1A1A),
    navInactiveIcon = Color(0xFFFFFFFF).copy(alpha = 0.78f),
    headerGradientStart = Color(0xFF0F0F0F),
    headerGradientEnd = Color(0xFF111111),
    metricDistanceBg = Color(0xFF2C2C2E),
    metricDistanceFg = Color(0xFFFF8C69),
    metricBlinkBg = Color(0xFF2C2C2E),
    metricBlinkFg = Color(0xFFA6E8B3),
    metricPostureBg = Color(0xFF2C2C2E),
    metricPostureFg = Color(0xFFD5C3F8),
    metricLyingBg = Color(0xFF2C2C2E),
    metricLyingFg = Color(0xFFFF8C69),
)

internal val DefaultEyeTypographyTokens = EyeTypographyTokens(
    heroTitle = tokenTextStyle(size = 40.sp, lineHeight = 46.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    pageTitle = tokenTextStyle(size = 34.sp, lineHeight = 40.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    sectionTitle = tokenTextStyle(size = 22.sp, lineHeight = 28.sp, weight = FontWeight.SemiBold),
    cardTitle = tokenTextStyle(size = 22.sp, lineHeight = 28.sp, weight = FontWeight.SemiBold),
    body = tokenTextStyle(size = 16.sp, lineHeight = 24.sp, weight = FontWeight.Normal, letterSpacing = 0.1.sp),
    bodyStrong = tokenTextStyle(size = 16.sp, lineHeight = 24.sp, weight = FontWeight.Medium, letterSpacing = 0.1.sp),
    bodySmall = tokenTextStyle(size = 13.sp, lineHeight = 18.sp, weight = FontWeight.Normal),
    label = tokenTextStyle(size = 13.sp, lineHeight = 18.sp, weight = FontWeight.Normal),
    caption = tokenTextStyle(size = 13.sp, lineHeight = 18.sp, weight = FontWeight.Normal),
)

private fun tokenTextStyle(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight,
    letterSpacing: TextUnit = 0.sp,
): TextStyle = TextStyle(
    fontSize = size,
    lineHeight = lineHeight,
    fontWeight = weight,
    letterSpacing = letterSpacing,
)

val LocalEyeColors = staticCompositionLocalOf { LightEyeColorTokens }
val LocalEyeTypography = staticCompositionLocalOf { DefaultEyeTypographyTokens }
val LocalEyeSpacing = staticCompositionLocalOf { EyeSpacingTokens() }
val LocalEyeRadius = staticCompositionLocalOf { EyeRadiusTokens() }
val LocalEyeElevation = staticCompositionLocalOf { EyeElevationTokens() }

internal fun eyeColorTokens(darkTheme: Boolean): EyeColorTokens {
    return if (darkTheme) DarkEyeColorTokens else LightEyeColorTokens
}

object EyeDesignTokens {
    val colors: EyeColorTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalEyeColors.current

    val typography: EyeTypographyTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalEyeTypography.current

    val spacing: EyeSpacingTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalEyeSpacing.current

    val radius: EyeRadiusTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalEyeRadius.current

    val elevation: EyeElevationTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalEyeElevation.current

    val cardShape: RoundedCornerShape
        @Composable
        @ReadOnlyComposable
        get() = RoundedCornerShape(radius.lg)

    val chipShape: RoundedCornerShape
        @Composable
        @ReadOnlyComposable
        get() = RoundedCornerShape(radius.pill)

    val headerGradient: Brush
        @Composable
        @ReadOnlyComposable
        get() = Brush.verticalGradient(
            colors = listOf(colors.headerGradientStart, colors.headerGradientEnd),
            startY = 0f,
            endY = 300f,
        )
}
