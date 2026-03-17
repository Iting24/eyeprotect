package com.example.eyeprotect.ui.theme

import android.os.Build
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
    onSecondary = Color.Black,
    background = DarkNavy,
    onBackground = TextWhite,
    surface = CardBackground,
    onSurface = TextWhite,
    surfaceVariant = Color(0xFF1C2128)
)

private val LightColorScheme = lightColorScheme(
    primary = AccentTeal,
    onPrimary = Color.White,
    secondary = AccentOrange,
    onSecondary = Color.Black,
    background = Color(0xFFF6FAFF),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFE2E8F0)
)

@Composable
fun EyeprotectTheme(
    darkTheme: Boolean = false,
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
