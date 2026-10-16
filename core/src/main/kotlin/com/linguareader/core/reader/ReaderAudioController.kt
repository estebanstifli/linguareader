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
