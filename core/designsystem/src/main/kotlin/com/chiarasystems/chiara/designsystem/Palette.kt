package com.chiarasystems.chiara.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Port of iOS `Palette` (Core/Theme.swift): every role as a light/dark pair, resolved once per theme. */
data class ChiaraColors(
    val primary: Color, val accent: Color, val background: Color, val surface: Color,
    val text: Color, val textMuted: Color, val textFaint: Color, val separator: Color,
    val destructive: Color, val disabled: Color, val field: Color, val chevron: Color,
    val chart2: Color, val chart3: Color, val chip: Color,
    val successFg: Color, val successBg: Color,
    val warningFg: Color, val warningBg: Color,
    val infoFg: Color, val infoBg: Color,
    val dark: Boolean,
) {
    /** Rotating avatar tints (background, foreground), indexed by `Patient.tintIndex`. */
    val avatarTints: List<Pair<Color, Color>>
        get() = listOf(accent to primary, infoBg to infoFg, successBg to successFg, warningBg to warningFg)
}

private fun c(hex: Long) = Color(0xFF000000 or hex)

val LightColors = ChiaraColors(
    primary = c(0x005BB6), accent = c(0xE8F1FC), background = c(0xF3F3F6), surface = c(0xFFFFFF),
    text = c(0x1D1D1F), textMuted = c(0x5E5E63), textFaint = c(0x8E8E93), separator = c(0xE2E2E6),
    destructive = c(0xB50012), disabled = c(0xAEAEB2), field = c(0xF0F0F3), chevron = c(0xC4C4C7),
    chart2 = c(0x3B8EE6), chart3 = c(0xB3D4F5), chip = c(0xEDEDF0),
    successFg = c(0x1C6C30), successBg = c(0xE3F5E8),
    warningFg = c(0x9A5B00), warningBg = c(0xFDF1DC),
    infoFg = c(0x007386), infoBg = c(0xE0F4F7),
    dark = false,
)

val DarkColors = ChiaraColors(
    primary = c(0x3D8BF0), accent = c(0x16283F), background = c(0x0B0B0E), surface = c(0x1C1C1F),
    text = c(0xF2F2F7), textMuted = c(0xA8A8AE), textFaint = c(0x8E8E93), separator = c(0x3A3A3E),
    destructive = c(0xFF5A64), disabled = c(0x55555A), field = c(0x2A2A2E), chevron = c(0x5A5A5E),
    chart2 = c(0x3B8EE6), chart3 = c(0x2B4A6E), chip = c(0x2C2C30),
    successFg = c(0x5FD07A), successBg = c(0x12301B),
    warningFg = c(0xF0B04A), warningBg = c(0x3A2B0C),
    infoFg = c(0x4CC3D6), infoBg = c(0x0E2F35),
    dark = true,
)

val LocalChiaraColors = staticCompositionLocalOf { LightColors }

/** `Palette.x` in iOS; `Palette.x` here too (read inside composition). */
val Palette: ChiaraColors
    @Composable @ReadOnlyComposable get() = LocalChiaraColors.current

object Radius {
    const val card = 22
    const val cardLarge = 24
    const val sheet = 44
}

@Composable
fun ChiaraTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.primary, primaryContainer = p.accent, background = p.background, surface = p.surface,
            surfaceVariant = p.field, onBackground = p.text, onSurface = p.text, onSurfaceVariant = p.textMuted,
            error = p.destructive, outlineVariant = p.separator,
        )
    } else {
        lightColorScheme(
            primary = p.primary, primaryContainer = p.accent, background = p.background, surface = p.surface,
            surfaceVariant = p.field, onBackground = p.text, onSurface = p.text, onSurfaceVariant = p.textMuted,
            error = p.destructive, outlineVariant = p.separator,
        )
    }
    CompositionLocalProvider(LocalChiaraColors provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
