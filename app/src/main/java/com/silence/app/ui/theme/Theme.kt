package com.silence.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkGreen = Color(0xFF1B5E20)
private val LightGreen = Color(0xFF4CAF50)
private val DarkBg = Color(0xFF121212)
private val DarkSurface = Color(0xFF1E1E1E)
private val LightBg = Color(0xFFF5F5F5)
private val LightSurface = Color(0xFFFFFFFF)

private val DarkColorScheme = darkColorScheme(
    primary = LightGreen,
    onPrimary = Color.Black,
    secondary = Color(0xFF81C784),
    background = DarkBg,
    surface = DarkSurface,
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFCF6679),
)

private val LightColorScheme = lightColorScheme(
    primary = DarkGreen,
    onPrimary = Color.White,
    secondary = Color(0xFF388E3C),
    background = LightBg,
    surface = LightSurface,
    onBackground = Color.Black,
    onSurface = Color.Black,
    error = Color(0xFFB00020),
)

@Composable
fun SilenceTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography(),
        content = content
    )
}
