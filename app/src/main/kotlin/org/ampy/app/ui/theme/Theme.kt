package org.ampy.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.ampy.app.data.ThemeMode

/** Colori specifici della workstation (forme d'onda, piano roll, cursore) oltre allo schema Material. */
@Immutable
data class StudioColors(
    val waveform: Color, val waveformBg: Color, val grid: Color, val gridStrong: Color,
    val note: Color, val noteLowConfidence: Color, val noteSelected: Color, val playhead: Color,
    val trackColors: List<Color>, val scoreFg: String, val scoreBg: String, val scoreAccent: String,
)

val LocalStudioColors = staticCompositionLocalOf { darkStudio }

private val lightScheme = lightColorScheme(
    primary = Color(0xFF3949AB), secondary = Color(0xFF00897B), tertiary = Color(0xFFD81B60),
    background = Color(0xFFF6F7FB), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE6E8F0),
)
private val darkScheme = darkColorScheme(
    primary = Color(0xFF8C9EFF), secondary = Color(0xFF64FFDA), tertiary = Color(0xFFFF80AB),
    background = Color(0xFF121318), surface = Color(0xFF1B1C22), surfaceVariant = Color(0xFF2A2C35),
)
private val cyberScheme = darkColorScheme(
    primary = Color(0xFFFF2E88), onPrimary = Color(0xFF12000A), secondary = Color(0xFF00E5FF), onSecondary = Color(0xFF001316),
    tertiary = Color(0xFFFFE600), background = Color(0xFF0B0D12), onBackground = Color(0xFFE6F7FF),
    surface = Color(0xFF12151D), onSurface = Color(0xFFE6F7FF), surfaceVariant = Color(0xFF1C2130), onSurfaceVariant = Color(0xFF9FB8C8),
    outline = Color(0xFF00E5FF).copy(alpha = 0.5f),
)

private val palette = listOf(Color(0xFFFF2E88), Color(0xFF00E5FF), Color(0xFFFFE600), Color(0xFF7CFF4F), Color(0xFFB388FF), Color(0xFFFF9100), Color(0xFF40C4FF), Color(0xFFFF5252))

private val lightStudio = StudioColors(Color(0xFF3949AB), Color(0xFFEEF0F7), Color(0x22000000), Color(0x55000000), Color(0xFF3949AB), Color(0xFFE57373), Color(0xFFD81B60), Color(0xFFD81B60), palette, "#111111", "#ffffff", "#d81b60")
private val darkStudio = StudioColors(Color(0xFF8C9EFF), Color(0xFF1B1C22), Color(0x22FFFFFF), Color(0x55FFFFFF), Color(0xFF8C9EFF), Color(0xFFFF8A80), Color(0xFFFF80AB), Color(0xFFFF80AB), palette, "#e8e8ee", "#1b1c22", "#ff80ab")
private val cyberStudio = StudioColors(Color(0xFF00E5FF), Color(0xFF0E1118), Color(0x2200E5FF), Color(0x66FF2E88), Color(0xFF00E5FF), Color(0xFFFF5252), Color(0xFFFFE600), Color(0xFFFF2E88), palette, "#e6f7ff", "#12151d", "#ff2e88")

private val typography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
)

fun schemeFor(mode: ThemeMode): ColorScheme = when (mode) { ThemeMode.LIGHT -> lightScheme; ThemeMode.DARK -> darkScheme; ThemeMode.CYBERPUNK -> cyberScheme }
fun studioFor(mode: ThemeMode): StudioColors = when (mode) { ThemeMode.LIGHT -> lightStudio; ThemeMode.DARK -> darkStudio; ThemeMode.CYBERPUNK -> cyberStudio }

@Composable
fun AmpyTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStudioColors provides studioFor(mode)) {
        MaterialTheme(colorScheme = schemeFor(mode), typography = typography, content = content)
    }
}
