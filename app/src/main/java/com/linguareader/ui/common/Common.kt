package com.linguareader.ui.common

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.linguareader.core.content.MediaResult
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.HighlightStyle
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.TermKind
import com.linguareader.core.model.ThemeMode
import java.util.Locale

object Languages {
    /** Idiomas de traducción ofrecidos en la UI (los datos pueden no existir para todos). */
    val translationTargets = listOf("es", "en", "fr", "de", "it", "pt", "nl", "ca")

    fun name(tag: String?): String = when (tag?.let(LanguageTags::primary)) {
        null -> "Todos"
        "es" -> "Español"
        "en" -> "English"
        "fr" -> "Français"
        else -> Locale.forLanguageTag(tag).getDisplayName(Locale.forLanguageTag("es")).replaceFirstChar { it.uppercase() }
    }

    fun nameInSpanish(tag: String): String = when (LanguageTags.primary(tag)) {
        "es" -> "español"; "en" -> "inglés"; "fr" -> "francés"
        else -> Locale.forLanguageTag(tag).getDisplayLanguage(Locale.forLanguageTag("es"))
    }
}

object Labels {
    fun displayMode(mode: DisplayMode) = when (mode) {
        DisplayMode.TRANSLATION_ON_TAP -> "Traducción al tocar"
        DisplayMode.TRANSLATION_BELOW -> "Traducción bajo cada frase"
    }

    fun displayModeHint(mode: DisplayMode) = when (mode) {
        DisplayMode.TRANSLATION_ON_TAP -> "Solo el texto del libro; toca una palabra para ver su traducción y la de su frase"
        DisplayMode.TRANSLATION_BELOW -> "Cada frase con su traducción debajo"
    }

    fun highlight(style: HighlightStyle) = when (style) {
        HighlightStyle.BACKGROUND -> "Fondo"
        HighlightStyle.UNDERLINE -> "Subrayado"
        HighlightStyle.MARKER -> "Marcador"
    }

    fun theme(mode: ThemeMode) = when (mode) {
        ThemeMode.SYSTEM -> "Sistema"
        ThemeMode.LIGHT -> "Claro"
        ThemeMode.DARK -> "Oscuro"
        ThemeMode.SEPIA -> "Sepia"
    }

    fun justification(mode: com.linguareader.core.model.TextJustification) = when (mode) {
        com.linguareader.core.model.TextJustification.LEFT -> "Izquierda"
        com.linguareader.core.model.TextJustification.JUSTIFIED -> "Justificado"
        com.linguareader.core.model.TextJustification.JUSTIFIED_HYPHENATED -> "Justif. + guiones"
    }

    fun orientation(mode: com.linguareader.core.model.ScreenOrientation) = when (mode) {
        com.linguareader.core.model.ScreenOrientation.PORTRAIT -> "Vertical"
        com.linguareader.core.model.ScreenOrientation.LANDSCAPE -> "Horizontal"
        com.linguareader.core.model.ScreenOrientation.AUTO -> "Automática"
    }

    fun termKind(kind: TermKind) = when (kind) {
        TermKind.WORD -> "Palabra"
        TermKind.PHRASAL_VERB -> "Phrasal verb"
        TermKind.EXPRESSION -> "Expresión"
        TermKind.CONTRACTION -> "Contracción"
    }

    fun unavailable(reason: TranslationResult.Reason, target: String) = when (reason) {
        TranslationResult.Reason.NO_ENTRY -> "Traducción al ${Languages.nameInSpanish(target)} no disponible en este libro."
        TranslationResult.Reason.SAME_LANGUAGE -> "El idioma de traducción es el mismo que el del libro."
        TranslationResult.Reason.LANGUAGE_PAIR_UNSUPPORTED -> "Este par de idiomas no está soportado."
        TranslationResult.Reason.OFFLINE -> "Requiere conexión (no disponible sin red)."
        TranslationResult.Reason.ENGINE_ERROR -> "El traductor ha fallado."
        TranslationResult.Reason.MODEL_MISSING -> "Falta descargar el traductor de este idioma (≈40 MB)."
    }

    fun audioUnavailable(reason: EngineUnavailableReason, languageTag: String): String {
        val lang = Languages.nameInSpanish(languageTag)
        return when (reason) {
            EngineUnavailableReason.ENGINE_NOT_INSTALLED, EngineUnavailableReason.INIT_FAILED ->
                "No hay motor de voz (TTS) disponible. Puedes leer y traducir sin audio."
            EngineUnavailableReason.LANGUAGE_DATA_MISSING ->
                "Falta la voz en $lang. Instálala para escuchar el texto; mientras tanto puedes leer y traducir."
            EngineUnavailableReason.LANGUAGE_NOT_SUPPORTED ->
                "El motor de voz no soporta el $lang. Puedes leer y traducir sin audio."
            EngineUnavailableReason.VOICE_NOT_FOUND -> "La voz elegida ya no está instalada."
            EngineUnavailableReason.SYNTHESIS_ERROR -> "El motor de voz ha fallado."
            EngineUnavailableReason.MODEL_NOT_INSTALLED ->
                "El modelo de Supertonic no está instalado. Descárgalo en Ajustes > Motor de voz o vuelve a la voz del sistema."
            EngineUnavailableReason.OUT_OF_MEMORY ->
                "No hay memoria suficiente para Supertonic en este dispositivo. Prueba con la voz del sistema."
        }
    }
}

/**
 * Decodifica una imagen reducida a [maxWidthPx] de ancho como mucho (miniaturas del catálogo). Llamar fuera del
 * hilo principal: decodificar decenas de portadas a tamaño completo bloquea la interfaz.
 */
fun MediaResult.toThumbnail(maxWidthPx: Int): ImageBitmap? = (this as? MediaResult.Ok)?.bytes?.let { bytes ->
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

fun MediaResult.toImageBitmap(): ImageBitmap? = (this as? MediaResult.Ok)?.bytes?.let { bytes ->
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
