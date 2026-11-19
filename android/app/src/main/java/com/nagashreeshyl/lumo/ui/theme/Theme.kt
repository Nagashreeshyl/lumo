package com.nagashreeshyl.lumo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Lumo's accent, same as the iPhone app (light / dark). */
val AccentLight = Color(0xFF0F9D8A)
val AccentDark = Color(0xFF34D1B8)

@Composable
fun LumoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = AccentDark, onPrimary = Color.White, secondary = AccentDark,
        background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF151515),
        surfaceContainerHigh = Color(0xFF1E1E1E), surfaceVariant = Color(0xFF232323), onSurfaceVariant = Color(0xFF9A9A9F),
        secondaryContainer = Color(0xFF2A2A2A), onSecondaryContainer = AccentDark,
    ) else lightColorScheme(
        primary = AccentLight, onPrimary = Color.White, secondary = AccentLight,
        background = Color.White, surface = Color.White, surfaceContainer = Color(0xFFF4F4F6),
        surfaceContainerHigh = Color(0xFFEDEDF0), surfaceVariant = Color(0xFFEDEDF0), onSurfaceVariant = Color(0xFF6E6E73),
        secondaryContainer = Color(0xFFEDEDF0), onSecondaryContainer = AccentLight,
    )
    MaterialTheme(colorScheme = colors, content = content)
}
