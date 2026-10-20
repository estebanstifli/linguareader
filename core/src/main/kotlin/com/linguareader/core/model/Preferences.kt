package com.linguareader.core.model

enum class DisplayMode {
    /** Solo el texto del libro; la traducción sale al tocar una palabra o frase. */
    TRANSLATION_ON_TAP,
    /** Traducción debajo de cada frase. */
    TRANSLATION_BELOW,
}

enum class HighlightStyle { BACKGROUND, UNDERLINE, MARKER }

enum class ThemeMode { SYSTEM, LIGHT, DARK, SEPIA }

/** Alineación del texto en el lector paginado. */
enum class TextJustification { LEFT, JUSTIFIED, JUSTIFIED_HYPHENATED }

/** Para qué usa la app el usuario (primera apertura). Decide la sección de inicio y qué se prepara. */
enum class AppPurpose { READ, LEARN, BOTH }

/** Orientación de la pantalla (se aplica a toda la app). */
enum class ScreenOrientation { PORTRAIT, LANDSCAPE, AUTO }

data class UserPreferences(
    /** Idioma que se aprende (elegido en la primera apertura); idioma por defecto de lo importado. null = sin elegir. */
    val readingLanguage: String? = null,
    /** Idioma nativo del usuario, al que se traduce. Por defecto, el del móvil (lo resuelve el repositorio). */
    val translationLanguage: String = "es",
    val displayMode: DisplayMode = DisplayMode.TRANSLATION_ON_TAP,
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
    /** Pasar números, abreviaturas y símbolos a palabras antes de la voz generada (Supertonic). */
    val normalizeSpeech: Boolean = true,
    /** Límite de la caché de audio generado y su alineación (MB). Se expulsa lo menos usado al superarlo. */
    val audioCacheMb: Int = 256,
    /** Ya se mostró la configuración inicial de traducción (elegir idioma y descargar su modelo). */
    val translationSetupDone: Boolean = false,
    /** Descargar modelos de traducción solo con Wi-Fi. */
    val translationWifiOnly: Boolean = true,
    /**
     * Modo aprendizaje con voz (TTS, resaltado, controles de reproducción). Desactivado = lector clásico:
     * no se genera audio ni se muestran los controles de reproducción.
     */
    val ttsMode: Boolean = true,
    /** Ya se preguntó al usuario, en la primera apertura, si quiere el modo con voz. */
    val ttsModeChosen: Boolean = false,
    /** Ya se preguntó, en la primera apertura del modo aprendizaje, qué idioma aprender (y se ofreció su biblioteca). */
    val learnLanguageChosen: Boolean = false,
    /** Elegido en la primera apertura; null en instalaciones anteriores a esa pregunta. */
    val purpose: AppPurpose? = null,
    /** Nivel MCER aproximado del que aprende (filtro inicial de «Aprender…»); null = «No lo sé». */
    val learnerLevel: CefrLevel? = null,
    /** Ya se preguntó el nivel en la primera apertura. */
    val learnerLevelChosen: Boolean = false,
    /** Ya se mostró el resumen final de la primera apertura («Esto es lo que hemos preparado»). */
    val onboardingSummaryShown: Boolean = false,
    val orientation: ScreenOrientation = ScreenOrientation.PORTRAIT,
    val textJustification: TextJustification = TextJustification.JUSTIFIED_HYPHENATED,
    /** Seguir leyendo en voz alta con la pantalla bloqueada (servicio en primer plano con notificación). */
    val readWithScreenOff: Boolean = true,
    /** Impedir que la pantalla se apague sola mientras el lector está abierto (el botón de encendido sigue funcionando). */
    val keepScreenOn: Boolean = true,
    /** Biblioteca en cuadrícula de portadas (true) o en lista de fichas (false). */
    val libraryGrid: Boolean = false,
) {
    companion object {
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 1.5f
        const val ANDROID_TTS_ENGINE = "android-tts"
    }
}
