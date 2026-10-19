package com.p2chat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = StarkPrimary,
    onPrimary = OnStarkPrimary,
    primaryContainer = SurfaceBright,
    onPrimaryContainer = TextPrimary,
    background = CanvasBackground,
    onBackground = TextPrimary,
    surface = SurfaceContainer,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceContainer,
    onSurfaceVariant = TextSecondary,
    outline = HairlineBorder,
    outlineVariant = HairlineBorder,
    error = DestructiveRose,
    onError = OnStarkPrimary,
    secondary = VerifiedGreen,
    onSecondary = OnStarkPrimary,
    tertiary = WarningAmber,
    onTertiary = OnStarkPrimary
)

@Composable
fun P2ChatTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
