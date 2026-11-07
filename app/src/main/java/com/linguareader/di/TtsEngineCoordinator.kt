package com.linguareader.di

import android.content.ComponentCallbacks2
import com.linguareader.core.reader.SpeechPauses
import com.linguareader.core.reader.TtsEngineRegistry
import com.linguareader.core.reader.TtsEngineSelector
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.alignment.RetiredAlignmentModels
import com.linguareader.engine.tts.SystemTtsAutoSelect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aplica las preferencias de motor (motor activo, pasos de Supertonic) y libera los modelos
 * pesados cuando el sistema pide memoria.
 */
class TtsEngineCoordinator(
    private val preferences: UserPreferencesRepository,
    private val selector: TtsEngineSelector,
    private val registry: TtsEngineRegistry,
    private val settings: EngineSettingsHolder,
    private val supertonic: SupertonicEngine,
    private val autoSelect: SystemTtsAutoSelect,
    private val retiredAligners: RetiredAlignmentModels,
    private val scope: CoroutineScope,
) {
    fun start() {
        // Únicamente los pesos de alineadores retirados; no toca libros, narraciones ni marcas ya guardadas.
        scope.launch(Dispatchers.IO) { retiredAligners.remove() }
        // Instalaciones que ya pasaron la primera apertura sin motor elegido (p. ej. con RHVoice por defecto).
        scope.launch { autoSelect.ensureChosen() }
        scope.launch {
            preferences.preferences.map { it.supertonicSteps }.distinctUntilChanged().collect {
                settings.supertonicSteps = SupertonicEngine.DEFAULT_STEPS
                if (it != SupertonicEngine.DEFAULT_STEPS) preferences.update { p -> p.copy(supertonicSteps = SupertonicEngine.DEFAULT_STEPS) }
            }
        }
        scope.launch {
            preferences.preferences.map { SpeechPauses(it.sentencePauseMs.toLong(), it.paragraphPauseMs.toLong()) }
                .distinctUntilChanged().collect { settings.pauses = it }
        }
        scope.launch {
            preferences.preferences.map { it.normalizeSpeech }.distinctUntilChanged().collect { settings.normalizeSpeech = it }
        }
        scope.launch {
            preferences.preferences.map { it.autoContinue }.distinctUntilChanged().collect { settings.autoContinue = it }
        }
        scope.launch {
            preferences.preferences.map { it.audioCacheMb }.distinctUntilChanged().collect { settings.audioCacheBytes = it * 1024L * 1024 }
        }
        scope.launch {
            preferences.preferences.map { it.systemTtsEngine }.distinctUntilChanged().collect { settings.systemTtsEngine = it }
        }
        scope.launch {
            preferences.preferences.map { it.ttsPitch }.distinctUntilChanged().collect { settings.ttsPitch = it }
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
