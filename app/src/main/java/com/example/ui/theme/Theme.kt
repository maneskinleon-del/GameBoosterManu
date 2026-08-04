package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryNeonCyan,
    onPrimary = TextDark,
    primaryContainer = PrimaryContainerCyan,
    onPrimaryContainer = TextOnSurface,
    secondary = SecondaryGreen,
    onSecondary = TextDark,
    secondaryContainer = SecondaryContainerGreen,
    onSecondaryContainer = TextOnSurface,
    background = BackgroundBlue,
    onBackground = TextOnSurface,
    surface = SurfaceBlue,
    onSurface = TextOnSurface,
    surfaceVariant = CardBlue,
    onSurfaceVariant = TextMuted,
    outline = TextMuted
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
