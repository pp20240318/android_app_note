package com.localnotes.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF1B4D3E)
private val GreenSoft = Color(0xFF2F6B57)
private val Cream = Color(0xFFF4F7F5)
private val Ink = Color(0xFF14231E)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    secondary = GreenSoft,
    background = Cream,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCBB5),
    onPrimary = Color(0xFF003828),
    secondary = Color(0xFFA8D5C3),
    background = Color(0xFF101916),
    surface = Color(0xFF1A2621),
    onBackground = Color(0xFFE6F0EB),
    onSurface = Color(0xFFE6F0EB)
)

@Composable
fun LocalNotesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
