package com.micu.studio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Orange = Color(0xFFFF6B57)
val OrangeDark = Color(0xFFE5532F)
val CreamBg = Color(0xFFFDF6F0)
val CreamBgDark = Color(0xFF1C1512)

private val LightColors = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    secondary = Color(0xFFFFAB40),
    background = CreamBg,
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF4E8E0)
)

private val DarkColors = darkColorScheme(
    primary = OrangeDark,
    onPrimary = Color.White,
    secondary = Color(0xFFFFAB40),
    background = CreamBgDark,
    surface = Color(0xFF2A211D),
    surfaceVariant = Color(0xFF3A2F29)
)

@Composable
fun ShengTuTaiTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
