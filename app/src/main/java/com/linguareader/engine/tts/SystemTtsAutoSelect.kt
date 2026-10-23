package com.linguareader.engine.tts

import android.util.Log
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.di.EngineSettingsHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Primera detección del motor de voz del sistema. Mientras el usuario no haya elegido motor, el predeterminado de
 * Android puede ser uno sin voces para el idioma (p. ej. RHVoice sin paquetes instalados): se elige Speech Services by
 * Google si está instalado y, si no, el primer motor (predeterminado primero) que tenga voz para el idioma de lectura.
 * La elección se guarda como la de Ajustes › Voz, así que solo ocurre una vez y el usuario la puede cambiar.
 */
class SystemTtsAutoSelect(
    private val preferences: UserPreferencesRepository,
    private val settings: EngineSettingsHolder,
    engine: StreamingSpeechEngine,
) {
    private val tts = engine as? AndroidTtsEngine
    private val mutex = Mutex()

    /** Elige y guarda el motor si aún no hay ninguno elegido; [languageTag] por defecto el de lectura. */
    suspend fun ensureChosen(languageTag: String? = null) = mutex.withLock {
        val t = tts ?: return@withLock
        val p = preferences.preferences.first()
        if (p.systemTtsEngine != null) return@withLock
        val engines = runCatching { t.engines() }.getOrDefault(emptyList())
        val lang = languageTag ?: p.readingLanguage
        val choice = engines.firstOrNull { it.packageName == AndroidTtsEngine.GOOGLE_TTS_PACKAGE }?.packageName
            ?: lang?.let { l -> engines.firstOrNull { runCatching { t.hasLanguage(it.packageName, l) }.getOrDefault(false) }?.packageName }
            ?: return@withLock
        Log.i(AndroidTtsEngine.TAG, "Motor de voz elegido en la primera detección: $choice")
        settings.systemTtsEngine = choice
        preferences.update { it.copy(systemTtsEngine = choice) }
    }
}
