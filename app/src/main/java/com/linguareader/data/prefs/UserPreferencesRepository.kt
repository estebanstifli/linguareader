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
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.HighlightStyle
import com.linguareader.core.model.ThemeMode
import com.linguareader.core.model.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
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
        val audioCacheMb = intPreferencesKey("audio_cache_mb")
        val packsToken = stringPreferencesKey("packs_token")
    }

    /**
     * Token de acceso al repositorio (privado) de paquetes. Se guarda aparte de [UserPreferences]
     * para que no circule por la UI ni los logs; vive en el almacenamiento privado de la app.
     */
    val packsToken: Flow<String> = context.userPrefsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.packsToken].orEmpty() }

    suspend fun setPacksToken(token: String) {
        context.userPrefsStore.edit { if (token.isBlank()) it.remove(Keys.packsToken) else it[Keys.packsToken] = token.trim() }
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
            stored[Keys.audioCacheMb] = next.audioCacheMb
        }
    }

    private fun fromPreferences(p: Preferences): UserPreferences {
        val d = UserPreferences()
        return UserPreferences(
            readingLanguage = p[Keys.readingLanguage],
            translationLanguage = p[Keys.translationLanguage] ?: d.translationLanguage,
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
            supertonicSteps = p[Keys.supertonicSteps] ?: d.supertonicSteps,
            supertonicLicenseAccepted = p[Keys.supertonicLicense] ?: d.supertonicLicenseAccepted,
            sentencePauseMs = p[Keys.sentencePause] ?: d.sentencePauseMs,
            paragraphPauseMs = p[Keys.paragraphPause] ?: d.paragraphPauseMs,
            audioCacheMb = p[Keys.audioCacheMb] ?: d.audioCacheMb,
        )
    }

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
