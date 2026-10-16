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
    /** Motor TTS activo (id de [com.linguareader.core.engine.TtsEngineInfo]). */
    val ttsEngineId: String = ANDROID_TTS_ENGINE,
    /** Voz de Supertonic (estilos multilingües F1–F5, M1–M5). */
    val supertonicVoice: String = "F1",
    /** Pasos de difusión de Supertonic: más = mejor calidad y más lento. */
    val supertonicSteps: Int = 5,
    /** El usuario aceptó la licencia OpenRAIL-M de los pesos antes de descargarlos. */
    val supertonicLicenseAccepted: Boolean = false,
    /** Pausa entre frases (ms) que inserta el reproductor (+~0,1 s de transición de ExoPlayer ≈ 0,65 s efectivos). */
    val sentencePauseMs: Int = 550,
    /** Pausa al terminar un párrafo/pasaje (ms). */
    val paragraphPauseMs: Int = 1000,
) {
    companion object {
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 1.5f
        const val ANDROID_TTS_ENGINE = "android-tts"
    }
}
