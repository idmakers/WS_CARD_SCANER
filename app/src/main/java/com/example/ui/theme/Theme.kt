package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val LightColorScheme =
  lightColorScheme(
    primary = PrimaryPurple,
    onPrimary = Color.White,
    primaryContainer = LightPurpleContainer,
    onPrimaryContainer = DarkPurple,
    background = ThemeBackground,
    onBackground = ThemeTextDark,
    surface = Color.White,
    onSurface = ThemeTextDark,
    surfaceVariant = Color(0xFFF3EDF7),
    onSurfaceVariant = Color(0xFF49454F),
    outline = BorderGray
  )

private val DarkColorScheme = LightColorScheme // Keep consistent for both Dark and Light to guarantee visual identity

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false, // Set to false to force our signature design theme colors consistently
  content: @Composable () -> Unit,
) {
  val colorScheme = LightColorScheme
  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
