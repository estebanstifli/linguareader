package com.linguareader.core.reader

import com.linguareader.core.engine.TtsEngine
import com.linguareader.core.model.Passage
import com.linguareader.core.narration.Narration
import com.linguareader.core.narration.NarrationSelector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Motores TTS disponibles, por id. Añadir un motor futuro = registrarlo aquí y en la DI. */
class TtsEngineRegistry(engines: List<TtsEngine>) {
    private val byId = engines.associateBy { it.id }
    val all: List<TtsEngine> = engines
    operator fun get(id: String): TtsEngine? = byId[id]
}

/**
 * Fachada que la UI usa como único [ReaderAudioController]: delega en el controlador del motor
 * seleccionado. Cambiar de motor detiene el anterior y libera sus recursos pesados.
 *
 * Si el libro abierto tiene narración pregrabada ([setNarration]), la lectura pasa a [narrated] mientras siga
 * activa; el motor TTS elegido se conserva y sigue dando el audio de las palabras sueltas.
 */
class SwitchingReaderAudioController(
    private val controllers: Map<String, ReaderAudioController>,
    private val engines: TtsEngineRegistry,
    defaultEngineId: String,
    private val dispatcher: CoroutineDispatcher,
    private val narrated: NarratedReaderAudioController? = null,
) : ReaderAudioController, TtsEngineSelector, NarrationSelector {

    init {
        require(defaultEngineId in controllers) { "Motor por defecto desconocido: $defaultEngineId" }
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _selected = MutableStateFlow(defaultEngineId)
    override val selectedEngineId: StateFlow<String> = _selected.asStateFlow()

    private var narrating = false
    private val engineController: ReaderAudioController get() = controllers.getValue(_selected.value)
    private val current: ReaderAudioController get() = narrated?.takeIf { narrating } ?: engineController
    private val _state = MutableStateFlow(current.playbackState.value)
    override val playbackState: StateFlow<PlaybackState> = _state.asStateFlow()
    private var mirror: Job? = null

    init {
        mirrorCurrent()
    }

    private fun mirrorCurrent() {
        mirror?.cancel()
        val source = current.playbackState
        mirror = scope.launch { source.collect { _state.value = it } }
    }

    override suspend fun selectEngine(engineId: String) = withContext(dispatcher) {
        if (engineId == _selected.value || engineId !in controllers) return@withContext
        val previousId = _selected.value
        engineController.stop()
        engines[previousId]?.release()
        _selected.value = engineId
        mirrorCurrent()
    }

    override suspend fun setNarration(narration: Narration?) = withContext(dispatcher) {
        val n = narrated ?: return@withContext
        if (narration == null && !narrating) return@withContext
        current.stop()
        n.attach(narration)
        narrating = narration != null
        mirrorCurrent()
    }

    override suspend fun load(passage: Passage, languageTag: String, voiceId: String?, startOffset: Int) =
        current.load(passage, languageTag, voiceId, startOffset)
    override suspend fun play() = current.play()
    override suspend fun pause() = current.pause()
    override suspend fun seekToSentence(index: Int) = current.seekToSentence(index)
    override suspend fun seekToOffset(charOffset: Int) = current.seekToOffset(charOffset)
    override suspend fun seekTo(milliseconds: Long) = current.seekTo(milliseconds)
    override suspend fun setSpeed(speed: Float) {
        // La velocidad se aplica a todos los motores para que cambiar de motor la conserve.
        controllers.values.forEach { it.setSpeed(speed) }
        narrated?.setSpeed(speed)
    }
    override suspend fun speakSnippet(text: String, languageTag: String) {
        if (narrating) narrated?.pause()
        engineController.speakSnippet(text, languageTag)
    }
    override suspend fun stop() = current.stop()
    override suspend fun prefetch(passages: List<Passage>, languageTag: String, voiceId: String?) =
        current.prefetch(passages, languageTag, voiceId)
    override suspend fun warmUp(passage: Passage, languageTag: String, voiceId: String?, policy: WarmUpPolicy, fromSentence: Int) =
        current.warmUp(passage, languageTag, voiceId, policy, fromSentence)
}
