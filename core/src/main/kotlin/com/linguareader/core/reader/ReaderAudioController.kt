package com.linguareader.core.reader

import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.model.Passage
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackStatus { IDLE, PREPARING, READY, PLAYING, PAUSED, COMPLETED, ERROR }

/** Qué se puede resaltar con honestidad: por palabra solo con tiempos/eventos fiables. */
enum class HighlightGranularity { NONE, SENTENCE, WORD }

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val passageId: String? = null,
    val sentenceIndex: Int = 0,
    val activeTokenId: String? = null,
    val granularity: HighlightGranularity = HighlightGranularity.NONE,
    /** Offset absoluto en el texto del pasaje desde el que se reanuda (inicio de la última palabra oída). */
    val resumeOffset: Int = 0,
    val speed: Float = 1.0f,
    val readiness: EngineReadiness = EngineReadiness.Ready,
    val engineId: String = "",
    val supportsSpeed: Boolean = false,
    val canSeekByTime: Boolean = false,
    /** true si el motor ha emitido eventos de palabra válidos en este pasaje. */
    val wordEventsObserved: Boolean = false,
    val errorCode: Int? = null,
    /** Generando audio de la frase que toca (motores renderizados). */
    val buffering: Boolean = false,
    /** El motor activo nunca entrega tiempos por palabra (p. ej. Supertonic). */
    val engineLacksWordTimings: Boolean = false,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
    val isAudioAvailable: Boolean get() = readiness is EngineReadiness.Ready
}

/**
 * Contrato que consume la UI del lector. Cambiar el motor TTS o el origen del audio (TTS directo,
 * audio sintetizado + alineación) no cambia esta interfaz ni la UI.
 */
interface ReaderAudioController {
    val playbackState: StateFlow<PlaybackState>

    /** Prepara un pasaje; no reproduce. [startOffset] es el punto de reanudación. */
    suspend fun load(passage: Passage, languageTag: String, voiceId: String?, startOffset: Int = 0)
    suspend fun play()
    suspend fun pause()
    suspend fun seekToSentence(index: Int)
    suspend fun seekToOffset(charOffset: Int)
    /** Solo si [PlaybackState.canSeekByTime]; devuelve false si no está soportado. */
    suspend fun seekTo(milliseconds: Long): Boolean
    suspend fun setSpeed(speed: Float)
    /** Pronuncia un fragmento suelto (audio de palabra). Pausa la lectura si estaba sonando. */
    suspend fun speakSnippet(text: String, languageTag: String)
    /** Detiene el audio conservando la posición (al salir del lector). */
    suspend fun stop()
    /** Prepara en segundo plano el audio de otro pasaje (normalmente el siguiente). */
    suspend fun prefetch(passage: Passage, languageTag: String, voiceId: String?) {}
    /**
     * Genera de antemano las primeras [sentenceCount] frases de [passage] (motores que sintetizan) para
     * que la reproducción arranque sin esperas. Suspende hasta tenerlas; false si la síntesis falló.
     */
    suspend fun warmUp(passage: Passage, languageTag: String, voiceId: String?, sentenceCount: Int = 2, fromSentence: Int = 0): Boolean = true
}

/** Pausas que inserta el reproductor (no se graban en el audio generado, así la caché no depende de ellas). */
data class SpeechPauses(val sentenceMs: Long = 550, val paragraphMs: Long = 1000)

/** Permite elegir el motor TTS activo sin que la UI conozca las implementaciones. */
interface TtsEngineSelector {
    val selectedEngineId: StateFlow<String>
    suspend fun selectEngine(engineId: String)
}

/** Foco de audio del sistema, abstraído para poder testear el controlador en JVM. */
interface AudioFocusGate {
    fun request(onLoss: () -> Unit): Boolean
    fun abandon()

    object AlwaysGranted : AudioFocusGate {
        override fun request(onLoss: () -> Unit) = true
        override fun abandon() {}
    }
}
