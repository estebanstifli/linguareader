package com.linguareader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.linguareader.core.model.ThemeMode

/** Colores específicos del lector (resaltado, traducción, dedo). */
@Immutable
data class ReaderColors(
    val sentenceHighlight: Color,
    val wordHighlight: Color,
    val onWordHighlight: Color,
    val underline: Color,
    val marker: Color,
    val translation: Color,
    val pointer: Color,
    val pointerOutline: Color,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2D5F73),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE6F0),
    onPrimaryContainer = Color(0xFF0B2B37),
    secondary = Color(0xFFB4582F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCB),
    onSecondaryContainer = Color(0xFF3A1406),
    background = Color(0xFFFBF8F1),
    onBackground = Color(0xFF1D1B18),
    surface = Color(0xFFFBF8F1),
    onSurface = Color(0xFF1D1B18),
    surfaceVariant = Color(0xFFECE6DA),
    onSurfaceVariant = Color(0xFF4C463C),
    surfaceContainer = Color(0xFFF2EDE3),
    surfaceContainerHigh = Color(0xFFECE6DA),
    outline = Color(0xFF7D766A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FCDE3),
    onPrimary = Color(0xFF00354A),
    primaryContainer = Color(0xFF1E4A5B),
    onPrimaryContainer = Color(0xFFCDE6F0),
    secondary = Color(0xFFFFB693),
    onSecondary = Color(0xFF55200A),
    secondaryContainer = Color(0xFF73351A),
    onSecondaryContainer = Color(0xFFFFDBCB),
    background = Color(0xFF14181B),
    onBackground = Color(0xFFE6E2DA),
    surface = Color(0xFF14181B),
    onSurface = Color(0xFFE6E2DA),
    surfaceVariant = Color(0xFF2A3136),
    onSurfaceVariant = Color(0xFFC4C7C9),
    surfaceContainer = Color(0xFF1C2226),
    surfaceContainerHigh = Color(0xFF242B30),
    outline = Color(0xFF8E9193),
)

private val LightReader = ReaderColors(
    sentenceHighlight = Color(0x332D5F73),
    wordHighlight = Color(0xFFFFD166),
    onWordHighlight = Color(0xFF1D1B18),
    underline = Color(0xFFB4582F),
    marker = Color(0x99FFD166),
    translation = Color(0xFF6A6358),
    pointer = Color(0xFFFFE3C2),
    pointerOutline = Color(0xFF8A5A3B),
)

private val DarkReader = ReaderColors(
    sentenceHighlight = Color(0x338FCDE3),
    wordHighlight = Color(0xFFE0B13E),
    onWordHighlight = Color(0xFF14181B),
    underline = Color(0xFFFFB693),
    marker = Color(0x80E0B13E),
    translation = Color(0xFFA8ADB0),
    pointer = Color(0xFFE8C9A8),
    pointerOutline = Color(0xFF2A1A10),
)

val LocalReaderColors = staticCompositionLocalOf { LightReader }

@Composable
fun LinguaReaderTheme(theme: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalReaderColors provides if (dark) DarkReader else LightReader) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
