package com.example.eyeprotect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = AccentTeal,
    onPrimary = Color.White,
    secondary = AccentOrange,
    onSecondary = Color(0xFF243043),
    primaryContainer = Color(0xFF233F63),
    onPrimaryContainer = Color(0xFFDCEBFF),
    secondaryContainer = Color(0xFF5A4B1A),
    onSecondaryContainer = Color(0xFFFFF0C5),
    background = Color(0xFF101923),
    onBackground = Color(0xFFEAF2FA),
    surface = Color(0xFF16212D),
    onSurface = Color(0xFFEAF2FA),
    surfaceVariant = Color(0xFF223140),
    onSurfaceVariant = Color(0xFFC2CEDC),
    outline = Color(0xFF43576E),
    error = Color(0xFFFF8E8E)
)

private val LightColorScheme = lightColorScheme(
    primary = AccentTeal,
    onPrimary = Color.White,
    secondary = AccentOrange,
    onSecondary = Color(0xFF202B3D),
    primaryContainer = Color(0xFFDDEBFF),
    onPrimaryContainer = Color(0xFF123A6B),
    secondaryContainer = Color(0xFFFFF1C8),
    onSecondaryContainer = Color(0xFF5C4100),
    background = Color(0xFFF2F7FC),
    onBackground = Color(0xFF202B3D),
    surface = Color.White,
    onSurface = Color(0xFF202B3D),
    surfaceVariant = Color(0xFFEAF1F8),
    onSurfaceVariant = Color(0xFF667085),
    outline = Color(0xFFDDE7F0),
    error = Color(0xFFE45A5A)
)

@Composable
fun EyeprotectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is disabled to ensure high contrast color-blind friendly colors are used
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
