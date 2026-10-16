package com.linguareader.core.model

enum class DisplayMode {
    /** Solo texto en el idioma de aprendizaje. */
    TEXT_ONLY,
    /** Traducción debajo de cada frase. */
    TRANSLATION_BELOW,
    /** La traducción de una frase aparece al tocarla. */
    TRANSLATION_ON_TAP,
    /** Pasaje original y pasaje traducido como bloques. */
    BILINGUAL_BLOCKS,
    /** Imagen grande al inicio del pasaje + texto. */
    ILLUSTRATED,
}

enum class HighlightStyle { BACKGROUND, UNDERLINE, MARKER }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserPreferences(
    /** Idioma de lectura (filtro del catálogo). null = todos. */
    val readingLanguage: String? = null,
    /** Idioma al que se traduce. Independiente del de lectura. */
    val translationLanguage: String = "es",
    val displayMode: DisplayMode = DisplayMode.TEXT_ONLY,
    val highlightStyle: HighlightStyle = HighlightStyle.BACKGROUND,
    val fingerPointerEnabled: Boolean = false,
    val autoScroll: Boolean = true,
    val showIllustrations: Boolean = true,
    val pauseOnWordTap: Boolean = true,
    val autoContinue: Boolean = true,
    val reduceMotion: Boolean = false,
    val speechRate: Float = 1.0f,
    /** Voz elegida por subetiqueta primaria de idioma. */
    val voiceByLanguage: Map<String, String> = emptyMap(),
    val fontScale: Float = 1.0f,
    val lineHeight: Float = 1.5f,
    val theme: ThemeMode = ThemeMode.SYSTEM,
) {
    companion object {
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 1.5f
    }
}
