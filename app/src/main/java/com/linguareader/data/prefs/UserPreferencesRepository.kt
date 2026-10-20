package com.linguareader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.HighlightStyle
import com.linguareader.core.model.ThemeMode
import com.linguareader.core.model.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

/** Preferencias persistentes. Idioma de lectura e idioma de traducción son claves independientes. */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val readingLanguage = stringPreferencesKey("reading_language")
        val translationLanguage = stringPreferencesKey("translation_language")
        val displayMode = stringPreferencesKey("display_mode")
        val highlightStyle = stringPreferencesKey("highlight_style")
        val finger = booleanPreferencesKey("finger_pointer")
        val autoScroll = booleanPreferencesKey("auto_scroll")
        val illustrations = booleanPreferencesKey("show_illustrations")
        val pauseOnTap = booleanPreferencesKey("pause_on_word_tap")
        val autoContinue = booleanPreferencesKey("auto_continue")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val speechRate = floatPreferencesKey("speech_rate")
        val voices = stringPreferencesKey("voice_by_language")
        val fontScale = floatPreferencesKey("font_scale")
        val lineHeight = floatPreferencesKey("line_height")
        val theme = stringPreferencesKey("theme")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val supertonicVoice = stringPreferencesKey("supertonic_voice")
        val supertonicSteps = intPreferencesKey("supertonic_steps")
        val supertonicLicense = booleanPreferencesKey("supertonic_license_accepted")
        val sentencePause = intPreferencesKey("sentence_pause_ms")
        val paragraphPause = intPreferencesKey("paragraph_pause_ms")
        val normalizeSpeech = booleanPreferencesKey("normalize_speech")
        val ttsMode = booleanPreferencesKey("tts_mode")
        val ttsModeChosen = booleanPreferencesKey("tts_mode_chosen")
        val learnLanguageChosen = booleanPreferencesKey("learn_language_chosen")
        val purpose = stringPreferencesKey("purpose")
        val learnerLevel = stringPreferencesKey("learner_level")
        val learnerLevelChosen = booleanPreferencesKey("learner_level_chosen")
        val onboardingSummaryShown = booleanPreferencesKey("onboarding_summary_shown")
        val orientation = stringPreferencesKey("orientation")
        val textJustification = stringPreferencesKey("text_justification")
        val readWithScreenOff = booleanPreferencesKey("read_with_screen_off")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val doublePage = booleanPreferencesKey("double_page")
        val libraryGrid = booleanPreferencesKey("library_grid")
        val audioCacheMb = intPreferencesKey("audio_cache_mb")
        val translationSetupDone = booleanPreferencesKey("translation_setup_done")
        val translationWifiOnly = booleanPreferencesKey("translation_wifi_only")
    }

    val preferences: Flow<UserPreferences> = context.userPrefsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::fromPreferences)

    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        context.userPrefsStore.edit { stored ->
            val next = transform(fromPreferences(stored))
            if (next.readingLanguage == null) stored.remove(Keys.readingLanguage)
            else stored[Keys.readingLanguage] = next.readingLanguage!!
            stored[Keys.translationLanguage] = next.translationLanguage
            stored[Keys.displayMode] = next.displayMode.name
            stored[Keys.highlightStyle] = next.highlightStyle.name
            stored[Keys.finger] = next.fingerPointerEnabled
            stored[Keys.autoScroll] = next.autoScroll
            stored[Keys.illustrations] = next.showIllustrations
            stored[Keys.pauseOnTap] = next.pauseOnWordTap
            stored[Keys.autoContinue] = next.autoContinue
            stored[Keys.reduceMotion] = next.reduceMotion
            stored[Keys.speechRate] = next.speechRate
            stored[Keys.voices] = encodeMap(next.voiceByLanguage)
            stored[Keys.fontScale] = next.fontScale
            stored[Keys.lineHeight] = next.lineHeight
            stored[Keys.theme] = next.theme.name
            stored[Keys.ttsEngine] = next.ttsEngineId
            stored[Keys.supertonicVoice] = next.supertonicVoice
            stored[Keys.supertonicSteps] = next.supertonicSteps
            stored[Keys.supertonicLicense] = next.supertonicLicenseAccepted
            stored[Keys.sentencePause] = next.sentencePauseMs
            stored[Keys.paragraphPause] = next.paragraphPauseMs
            stored[Keys.normalizeSpeech] = next.normalizeSpeech
            stored[Keys.ttsMode] = next.ttsMode
            stored[Keys.ttsModeChosen] = next.ttsModeChosen
            stored[Keys.learnLanguageChosen] = next.learnLanguageChosen
            next.purpose?.let { stored[Keys.purpose] = it.name } ?: stored.remove(Keys.purpose)
            next.learnerLevel?.let { stored[Keys.learnerLevel] = it.name } ?: stored.remove(Keys.learnerLevel)
            stored[Keys.learnerLevelChosen] = next.learnerLevelChosen
            stored[Keys.onboardingSummaryShown] = next.onboardingSummaryShown
            stored[Keys.orientation] = next.orientation.name
            stored[Keys.textJustification] = next.textJustification.name
            stored[Keys.readWithScreenOff] = next.readWithScreenOff
            stored[Keys.keepScreenOn] = next.keepScreenOn
            stored[Keys.doublePage] = next.doublePage
            stored[Keys.libraryGrid] = next.libraryGrid
            stored[Keys.audioCacheMb] = next.audioCacheMb
            stored[Keys.translationSetupDone] = next.translationSetupDone
            stored[Keys.translationWifiOnly] = next.translationWifiOnly
        }
    }

    private fun fromPreferences(p: Preferences): UserPreferences {
        val d = UserPreferences()
        return UserPreferences(
            readingLanguage = p[Keys.readingLanguage],
            translationLanguage = p[Keys.translationLanguage] ?: deviceLanguage(),
            // Los modos retirados (solo texto, bilingüe, ilustrado) pasan al modo por defecto.
            displayMode = enumOr(p[Keys.displayMode], d.displayMode),
            highlightStyle = enumOr(p[Keys.highlightStyle], d.highlightStyle),
            fingerPointerEnabled = p[Keys.finger] ?: d.fingerPointerEnabled,
            autoScroll = p[Keys.autoScroll] ?: d.autoScroll,
            showIllustrations = p[Keys.illustrations] ?: d.showIllustrations,
            pauseOnWordTap = p[Keys.pauseOnTap] ?: d.pauseOnWordTap,
            autoContinue = p[Keys.autoContinue] ?: d.autoContinue,
            reduceMotion = p[Keys.reduceMotion] ?: d.reduceMotion,
            speechRate = p[Keys.speechRate] ?: d.speechRate,
            voiceByLanguage = decodeMap(p[Keys.voices]),
            fontScale = p[Keys.fontScale] ?: d.fontScale,
            lineHeight = p[Keys.lineHeight] ?: d.lineHeight,
            theme = enumOr(p[Keys.theme], d.theme),
            ttsEngineId = p[Keys.ttsEngine] ?: d.ttsEngineId,
            supertonicVoice = p[Keys.supertonicVoice] ?: d.supertonicVoice,
            supertonicSteps = (p[Keys.supertonicSteps] ?: d.supertonicSteps).coerceAtLeast(d.supertonicSteps), // «Rápida» (3) ya no existe
            supertonicLicenseAccepted = p[Keys.supertonicLicense] ?: d.supertonicLicenseAccepted,
            sentencePauseMs = p[Keys.sentencePause] ?: d.sentencePauseMs,
            paragraphPauseMs = p[Keys.paragraphPause] ?: d.paragraphPauseMs,
            normalizeSpeech = p[Keys.normalizeSpeech] ?: d.normalizeSpeech,
            ttsMode = p[Keys.ttsMode] ?: d.ttsMode,
            ttsModeChosen = p[Keys.ttsModeChosen] ?: d.ttsModeChosen,
            learnLanguageChosen = p[Keys.learnLanguageChosen] ?: d.learnLanguageChosen,
            purpose = p[Keys.purpose]?.let { n -> AppPurpose.entries.firstOrNull { it.name == n } },
            learnerLevel = p[Keys.learnerLevel]?.let { n -> CefrLevel.entries.firstOrNull { it.name == n } },
            learnerLevelChosen = p[Keys.learnerLevelChosen] ?: d.learnerLevelChosen,
            onboardingSummaryShown = p[Keys.onboardingSummaryShown] ?: d.onboardingSummaryShown,
            orientation = enumOr(p[Keys.orientation], d.orientation),
            textJustification = enumOr(p[Keys.textJustification], d.textJustification),
            readWithScreenOff = p[Keys.readWithScreenOff] ?: d.readWithScreenOff,
            keepScreenOn = p[Keys.keepScreenOn] ?: d.keepScreenOn,
            doublePage = p[Keys.doublePage] ?: d.doublePage,
            libraryGrid = p[Keys.libraryGrid] ?: d.libraryGrid,
            audioCacheMb = p[Keys.audioCacheMb] ?: d.audioCacheMb,
            translationSetupDone = p[Keys.translationSetupDone] ?: d.translationSetupDone,
            translationWifiOnly = p[Keys.translationWifiOnly] ?: d.translationWifiOnly,
        )
    }

    /** Idioma del móvil como idioma nativo por defecto (subetiqueta primaria, p. ej. «es» de «es-ES»). */
    private fun deviceLanguage(): String =
        Locale.getDefault().language.takeIf { it.isNotEmpty() && it != "und" } ?: UserPreferences().translationLanguage

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun encodeMap(map: Map<String, String>): String =
        map.entries.joinToString(RECORD) { "${it.key}$FIELD${it.value}" }

    private fun decodeMap(raw: String?): Map<String, String> =
        raw.orEmpty().split(RECORD).mapNotNull { rec ->
            val parts = rec.split(FIELD)
            if (parts.size == 2 && parts[0].isNotEmpty()) parts[0] to parts[1] else null
        }.toMap()

    private companion object {
        const val RECORD = "\u001E"
        const val FIELD = "\u001F"
    }
}
