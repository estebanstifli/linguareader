package com.linguareader.ui.common

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import com.linguareader.R
import com.linguareader.core.content.MediaResult
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.HighlightStyle
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.TermKind
import com.linguareader.core.model.ThemeMode
import com.linguareader.core.model.ScreenOrientation
import com.linguareader.core.model.TextJustification
import com.linguareader.data.library.LibraryRepository
import com.linguareader.data.library.Shelf
import java.util.Locale

object Languages {
    /** Idiomas de traducción ofrecidos en la UI (los datos pueden no existir para todos). */
    val translationTargets = listOf("es", "en", "fr", "de", "it", "pt", "nl", "ca")

    /** Idioma de la interfaz (el del dispositivo o el elegido en los ajustes de Android 13+). */
    private val uiLocale: Locale get() = Locale.getDefault()

    /** Nombre de [tag] para mostrarlo suelto (selectores, chips): «Spanish» / «Español». */
    fun name(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayName(uiLocale).replaceFirstChar { it.titlecase(uiLocale) }

    /** Nombre de [tag] dentro de una frase, con la mayúscula propia del idioma: «Spanish» / «español». */
    fun nameInSentence(tag: String): String = Locale.forLanguageTag(tag).getDisplayLanguage(uiLocale)
}

object Labels {
    /** Las estanterías fijas se guardan con un nombre en español; se muestran con el del idioma de la app. */
    @Composable
    fun shelf(shelf: Shelf): String = when (shelf.kind) {
        LibraryRepository.FAVORITES -> stringResource(R.string.shelf_favorites)
        LibraryRepository.TO_READ -> stringResource(R.string.shelf_to_read)
        LibraryRepository.READ -> stringResource(R.string.shelf_read)
        else -> shelf.name
    }

    @Composable
    fun displayMode(mode: DisplayMode) = stringResource(
        when (mode) {
            DisplayMode.TRANSLATION_ON_TAP -> R.string.label_display_mode_on_tap
            DisplayMode.TRANSLATION_BELOW -> R.string.label_display_mode_below
        }
    )

    @Composable
    fun displayModeHint(mode: DisplayMode) = stringResource(
        when (mode) {
            DisplayMode.TRANSLATION_ON_TAP -> R.string.label_display_mode_on_tap_hint
            DisplayMode.TRANSLATION_BELOW -> R.string.label_display_mode_below_hint
        }
    )

    @Composable
    fun highlight(style: HighlightStyle) = stringResource(
        when (style) {
            HighlightStyle.BACKGROUND -> R.string.label_highlight_background
            HighlightStyle.UNDERLINE -> R.string.label_highlight_underline
            HighlightStyle.MARKER -> R.string.label_highlight_marker
        }
    )

    @Composable
    fun theme(mode: ThemeMode) = stringResource(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.label_theme_system
            ThemeMode.LIGHT -> R.string.label_theme_light
            ThemeMode.DARK -> R.string.label_theme_dark
            ThemeMode.SEPIA -> R.string.label_theme_sepia
        }
    )

    @Composable
    fun justification(mode: TextJustification) = stringResource(
        when (mode) {
            TextJustification.LEFT -> R.string.label_justify_left
            TextJustification.JUSTIFIED -> R.string.label_justify_justified
            TextJustification.JUSTIFIED_HYPHENATED -> R.string.label_justify_hyphenated
        }
    )

    @Composable
    fun orientation(mode: ScreenOrientation) = stringResource(
        when (mode) {
            ScreenOrientation.PORTRAIT -> R.string.label_orientation_portrait
            ScreenOrientation.LANDSCAPE -> R.string.label_orientation_landscape
            ScreenOrientation.AUTO -> R.string.label_orientation_auto
        }
    )

    @Composable
    fun termKind(kind: TermKind) = stringResource(
        when (kind) {
            TermKind.WORD -> R.string.label_term_word
            TermKind.PHRASAL_VERB -> R.string.label_term_phrasal_verb
            TermKind.EXPRESSION -> R.string.label_term_expression
            TermKind.CONTRACTION -> R.string.label_term_contraction
        }
    )

    @Composable
    fun unavailable(reason: TranslationResult.Reason, target: String) = when (reason) {
        TranslationResult.Reason.NO_ENTRY ->
            stringResource(R.string.translation_unavailable_no_entry, Languages.nameInSentence(target))
        TranslationResult.Reason.SAME_LANGUAGE -> stringResource(R.string.translation_unavailable_same_language)
        TranslationResult.Reason.LANGUAGE_PAIR_UNSUPPORTED -> stringResource(R.string.translation_unavailable_pair)
        TranslationResult.Reason.OFFLINE -> stringResource(R.string.translation_unavailable_offline)
        TranslationResult.Reason.ENGINE_ERROR -> stringResource(R.string.translation_unavailable_engine_error)
        TranslationResult.Reason.MODEL_MISSING -> stringResource(R.string.translation_unavailable_model_missing)
    }

    @Composable
    fun audioUnavailable(reason: EngineUnavailableReason, languageTag: String): String {
        val lang = Languages.nameInSentence(languageTag)
        return when (reason) {
            EngineUnavailableReason.ENGINE_NOT_INSTALLED, EngineUnavailableReason.INIT_FAILED ->
                stringResource(R.string.audio_unavailable_no_engine)
            EngineUnavailableReason.LANGUAGE_DATA_MISSING -> stringResource(R.string.audio_unavailable_voice_missing, lang)
            EngineUnavailableReason.LANGUAGE_NOT_SUPPORTED -> stringResource(R.string.audio_unavailable_language, lang)
            EngineUnavailableReason.VOICE_NOT_FOUND -> stringResource(R.string.audio_unavailable_voice_not_found)
            EngineUnavailableReason.SYNTHESIS_ERROR -> stringResource(R.string.audio_unavailable_synthesis_error)
            EngineUnavailableReason.MODEL_NOT_INSTALLED -> stringResource(R.string.audio_unavailable_model_missing)
            EngineUnavailableReason.OUT_OF_MEMORY -> stringResource(R.string.audio_unavailable_out_of_memory)
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
