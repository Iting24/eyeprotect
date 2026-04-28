package com.example.eyeprotect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun EyeprotectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val tokens = eyeColorTokens(darkTheme)

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = tokens.accentSecondary,
            onPrimary = tokens.textOnInverse,
            secondary = tokens.warning,
            onSecondary = tokens.textOnInverse,
            primaryContainer = tokens.surfaceSubtle,
            onPrimaryContainer = tokens.textPrimary,
            secondaryContainer = tokens.backgroundElevated,
            onSecondaryContainer = tokens.textPrimary,
            background = tokens.background,
            onBackground = tokens.textPrimary,
            surface = tokens.surface,
            onSurface = tokens.textPrimary,
            surfaceVariant = tokens.surfaceSubtle,
            onSurfaceVariant = tokens.textSecondary,
            outline = tokens.borderSubtle,
            error = tokens.error
        )
    } else {
        lightColorScheme(
            primary = tokens.accentPrimary,
            onPrimary = tokens.textOnInverse,
            secondary = tokens.accentSecondary,
            onSecondary = tokens.textOnInverse,
            primaryContainer = tokens.surfaceSubtle,
            onPrimaryContainer = tokens.textPrimary,
            secondaryContainer = tokens.backgroundElevated,
            onSecondaryContainer = tokens.textPrimary,
            background = tokens.background,
            onBackground = tokens.textPrimary,
            surface = tokens.surface,
            onSurface = tokens.textPrimary,
            surfaceVariant = tokens.surfaceSubtle,
            onSurfaceVariant = tokens.textSecondary,
            outline = tokens.borderSubtle,
            error = tokens.error
        )
    }

    CompositionLocalProvider(
        LocalEyeColors provides tokens,
        LocalEyeTypography provides DefaultEyeTypographyTokens,
        LocalEyeSpacing provides EyeSpacingTokens(),
        LocalEyeRadius provides EyeRadiusTokens(),
        LocalEyeElevation provides EyeElevationTokens(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
