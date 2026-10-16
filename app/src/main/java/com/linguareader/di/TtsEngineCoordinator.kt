package com.linguareader.di

import android.content.ComponentCallbacks2
import com.linguareader.core.reader.SpeechPauses
import com.linguareader.core.reader.TtsEngineRegistry
import com.linguareader.core.reader.TtsEngineSelector
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.supertonic.SupertonicEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aplica las preferencias de motor (motor activo, pasos de Supertonic) y libera los modelos
 * pesados cuando el sistema pide memoria.
 */
@Singleton
class TtsEngineCoordinator @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val selector: TtsEngineSelector,
    private val registry: TtsEngineRegistry,
    private val settings: EngineSettingsHolder,
    private val supertonic: SupertonicEngine,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            preferences.preferences.map { it.supertonicSteps }.distinctUntilChanged().collect { settings.supertonicSteps = it }
        }
        scope.launch {
            preferences.preferences.map { SpeechPauses(it.sentencePauseMs.toLong(), it.paragraphPauseMs.toLong()) }
                .distinctUntilChanged().collect { settings.pauses = it }
        }
        scope.launch {
            preferences.preferences.map { it.ttsEngineId }.distinctUntilChanged().collect { id ->
                val target = if (registry[id] != null) id else registry.all.first().id
                withContext(Dispatchers.Main) { selector.selectEngine(target) }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        // Liberar las sesiones ONNX (~500 MB) si el sistema está en apuros; se recargan al volver a sintetizar.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) supertonic.release()
    }
}
