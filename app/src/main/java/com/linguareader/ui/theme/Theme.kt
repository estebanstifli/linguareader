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
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3EB),
    surfaceContainer = Color(0xFFF2EDE3),
    surfaceContainerHigh = Color(0xFFECE6DA),
    surfaceContainerHighest = Color(0xFFE6E0D3),
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
    surfaceContainerLowest = Color(0xFF0F1215),
    surfaceContainerLow = Color(0xFF181D21),
    surfaceContainer = Color(0xFF1C2226),
    surfaceContainerHigh = Color(0xFF242B30),
    surfaceContainerHighest = Color(0xFF2E363C),
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

/** Papel envejecido y tinta marrón: menos contraste y luz azul para leer mucho rato. */
private val SepiaScheme = lightColorScheme(
    primary = Color(0xFF7A5230),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBD8BC),
    onPrimaryContainer = Color(0xFF2E1B0B),
    secondary = Color(0xFF8C5A3C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0DCC8),
    onSecondaryContainer = Color(0xFF331A0B),
    background = Color(0xFFF1E4CC),
    onBackground = Color(0xFF4A3424),
    surface = Color(0xFFF1E4CC),
    onSurface = Color(0xFF4A3424),
    surfaceVariant = Color(0xFFE5D3B6),
    onSurfaceVariant = Color(0xFF6B5442),
    surfaceContainerLowest = Color(0xFFF7EDDC),
    surfaceContainerLow = Color(0xFFF0E2C9),
    surfaceContainer = Color(0xFFEADAC0),
    surfaceContainerHigh = Color(0xFFE3D0B2),
    surfaceContainerHighest = Color(0xFFDCC8A8),
    outline = Color(0xFF8E7863),
)

private val SepiaReader = ReaderColors(
    sentenceHighlight = Color(0x337A5230),
    wordHighlight = Color(0xFFE9B868),
    onWordHighlight = Color(0xFF2E1B0B),
    underline = Color(0xFF9C4A1F),
    marker = Color(0x99E9B868),
    translation = Color(0xFF7D6650),
    pointer = Color(0xFFFFE3C2),
    pointerOutline = Color(0xFF6B4226),
)

val LocalReaderColors = staticCompositionLocalOf { LightReader }

@Composable
fun LinguaReaderTheme(theme: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val (scheme, reader) = when (theme) {
        ThemeMode.SYSTEM -> if (systemDark) DarkScheme to DarkReader else LightScheme to LightReader
        ThemeMode.LIGHT -> LightScheme to LightReader
        ThemeMode.DARK -> DarkScheme to DarkReader
        ThemeMode.SEPIA -> SepiaScheme to SepiaReader
    }
    CompositionLocalProvider(LocalReaderColors provides reader) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
