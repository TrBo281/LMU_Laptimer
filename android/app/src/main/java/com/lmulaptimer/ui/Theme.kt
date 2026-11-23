package com.lmulaptimer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LimeRaw = Color(0xFFB6FF3B)
private val CyanRaw = Color(0xFF6FD3FF)
private val WarnRaw = Color(0xFFFFB74D)
private val BadRaw = Color(0xFFFF5252)
private val BgRaw = Color(0xFF0B0B0B)
private val Surface1Raw = Color(0xFF121212)
private val Surface2Raw = Color(0xFF1B1B1B)
private val LineRaw = Color(0xFF2A2A2A)
private val TextMainRaw = Color(0xFFE9E9E9)
private val TextDimRaw = Color(0xFF9A9A9A)

/** Palette used by the pitwall UI. Night mode swaps it for a dim red set. */
data class PitColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val dim: Color,
    val accent: Color,
    val cyan: Color,
    val warn: Color,
    val bad: Color,
)

val DayPit = PitColors(
    bg = BgRaw,
    surface1 = Surface1Raw,
    surface2 = Surface2Raw,
    line = LineRaw,
    text = TextMainRaw,
    dim = TextDimRaw,
    accent = LimeRaw,
    cyan = CyanRaw,
    warn = WarnRaw,
    bad = BadRaw,
)

/** Red-on-black night palette: keeps the rig readable without white glare. */
val NightPit = PitColors(
    bg = Color(0xFF030303),
    surface1 = Color(0xFF090909),
    surface2 = Color(0xFF111111),
    line = Color(0xFF1D1D1D),
    text = Color(0xFFD9CFC4),
    dim = Color(0xFF8A7F74),
    accent = Color(0xFFFF6B5A),
    cyan = Color(0xFF6FA8C8),
    warn = Color(0xFFE0954A),
    bad = Color(0xFFFF4D4D),
)

val LocalPitColors = staticCompositionLocalOf { DayPit }

val Bg: Color
    @Composable @ReadOnlyComposable get() = LocalPitColors.current.bg

val Surface1: Color
    @Composable @ReadOnlyComposable get() = LocalPitColors.current.surface1

val Surface2: Color
    @Composable @ReadOnlyComposable get() = LocalPitColors.current.surface2

val Line: Color
    @Composable @ReadOnlyComposable get() = LocalPitColors.current.line

val TextMain: Color
    @Composable @ReadOnlyComposable get() = LocalPitColors.current.text

val TextDim: Color
    @Composable @ReadOnlyComposable get() = LocalPitColors.current.dim

val Lime: Color
    @Composable @ReadOnlyComposable get() = LocalPitColors.current.accent

val Cyan: Color
    @Composable @ReadOnlyComposable get() = LocalPitColors.current.cyan

val Warn: Color
    @Composable @ReadOnlyComposable get() = LocalPitColors.current.warn

val Bad: Color
    @Composable @ReadOnlyComposable get() = LocalPitColors.current.bad

private fun scheme(colors: PitColors) = darkColorScheme(
    primary = colors.accent,
    onPrimary = colors.bg,
    secondary = colors.cyan,
    onSecondary = colors.bg,
    background = colors.bg,
    onBackground = colors.text,
    surface = colors.surface1,
    onSurface = colors.text,
    surfaceVariant = colors.surface2,
    onSurfaceVariant = colors.dim,
    outline = colors.line,
    error = colors.bad,
    onError = colors.bg,
)

@Composable
fun LmuTheme(night: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (night) NightPit else DayPit
    CompositionLocalProvider(LocalPitColors provides colors) {
        MaterialTheme(colorScheme = scheme(colors), content = content)
    }
}
