package com.chiarasystems.chiara.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Port of iOS `Palette` (Core/Theme.swift). Values are light / dark. */
object Palette {
    val PrimaryLight = Color(0xFF005BB6);     val PrimaryDark = Color(0xFF3D8BF0)
    val AccentLight = Color(0xFFE8F1FC);      val AccentDark = Color(0xFF16283F)
    val BackgroundLight = Color(0xFFF3F3F6);  val BackgroundDark = Color(0xFF0B0B0E)
    val SurfaceLight = Color(0xFFFFFFFF);     val SurfaceDark = Color(0xFF1C1C1F)
    val TextLight = Color(0xFF1D1D1F);        val TextDark = Color(0xFFF2F2F7)
    val TextMutedLight = Color(0xFF5E5E63);   val TextMutedDark = Color(0xFFA8A8AE)
    val DestructiveLight = Color(0xFFB50012); val DestructiveDark = Color(0xFFFF5A64)
    val SeparatorLight = Color(0xFFE2E2E6);   val SeparatorDark = Color(0xFF3A3A3E)
    // TODO(MIGRATION Phase 3): remaining roles (success/warning/info fg+bg, chip, field, chevron, chart2/3) via a CompositionLocal.
}

private val Light = lightColorScheme(
    primary = Palette.PrimaryLight, primaryContainer = Palette.AccentLight,
    background = Palette.BackgroundLight, surface = Palette.SurfaceLight,
    onBackground = Palette.TextLight, onSurface = Palette.TextLight,
    onSurfaceVariant = Palette.TextMutedLight, error = Palette.DestructiveLight,
    outlineVariant = Palette.SeparatorLight,
)
private val Dark = darkColorScheme(
    primary = Palette.PrimaryDark, primaryContainer = Palette.AccentDark,
    background = Palette.BackgroundDark, surface = Palette.SurfaceDark,
    onBackground = Palette.TextDark, onSurface = Palette.TextDark,
    onSurfaceVariant = Palette.TextMutedDark, error = Palette.DestructiveDark,
    outlineVariant = Palette.SeparatorDark,
)

@Composable
fun ChiaraTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
