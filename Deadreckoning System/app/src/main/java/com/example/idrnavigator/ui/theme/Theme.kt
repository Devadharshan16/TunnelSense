package com.example.idrnavigator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CockpitColorScheme = darkColorScheme(
    primary = VehicleMarkerAccent,
    background = CockpitBackground,
    surface = CockpitSurface,
    onPrimary = CockpitBackground,
    onBackground = CockpitPrimaryText,
    onSurface = CockpitPrimaryText,
    surfaceVariant = CockpitSurface,
    onSurfaceVariant = CockpitSecondaryText,
    outline = CockpitDivider
)

@Composable
fun IDRNavigatorTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CockpitColorScheme,
        typography = Typography,
        content = content
    )
}