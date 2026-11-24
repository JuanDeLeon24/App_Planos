package com.cadfield.viewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paleta de alto contraste pensada para uso en exteriores (sol directo) y túneles (baja luz). */
val SafetyAmber = Color(0xFFF2A900)
val Steel = Color(0xFF0F2A3D)
val SkyBlue = Color(0xFF4FC3F7)
val AlertRed = Color(0xFFE53935)

private val Dark = darkColorScheme(
    primary = SafetyAmber, onPrimary = Color.Black,
    secondary = SkyBlue, onSecondary = Color.Black,
    background = Color(0xFF0B1620), surface = Color(0xFF13222F), onSurface = Color(0xFFE6EEF4),
    error = AlertRed,
)
private val Light = lightColorScheme(
    primary = Steel, onPrimary = Color.White,
    secondary = Color(0xFF00658E), background = Color(0xFFF7F9FB), surface = Color.White,
    error = AlertRed,
)

@Composable
fun CadFieldTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
