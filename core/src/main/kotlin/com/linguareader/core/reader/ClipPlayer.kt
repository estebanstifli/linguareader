package com.linguareader.core.reader

import kotlinx.coroutines.flow.Flow

sealed interface ClipEvent {
    /** Empieza a sonar el clip con esta etiqueta (índice de frase). */
    data class Started(val tag: Int) : ClipEvent
    /** Se reprodujeron todos los clips encolados (puede haber más en camino). */
    data object QueueEnded : ClipEvent
    data class Error(val message: String) : ClipEvent
}

/** Posición dentro del clip [tag] que suena ahora mismo. */
data class ClipPosition(val tag: Int, val positionMs: Long)

/**
 * Reproductor de una cola de clips de audio (implementación Android: Media3/ExoPlayer).
 * Las llamadas se hacen desde un único hilo (el principal en Android).
 */
interface ClipPlayer {
    val events: Flow<ClipEvent>
    /** Añade un clip al final de la cola; si la cola se había agotado y se estaba reproduciendo, continúa. */
    fun enqueue(tag: Int, path: String)
    /** Añade un silencio al final de la cola (pausa entre frases o párrafos). No emite [ClipEvent.Started]. */
    fun enqueueSilence(durationMs: Long)
    fun clear()
    fun play()
    fun pause()
    /** Salta al inicio del clip [tag] si está en la cola. */
    fun seekToTag(tag: Int): Boolean
    /** Salta a [positionMs] dentro del clip [tag] si está en la cola (pistas largas: narración pregrabada). */
    fun seekTo(tag: Int, positionMs: Long): Boolean = false
    /** Clip que suena ahora y su posición; null durante una pausa (silencio), en reposo o si el reproductor no lo informa. */
    fun position(): ClipPosition? = null
    /** Velocidad de reproducción (sin cambiar el tono). El audio no se regenera. */
    fun setPlaybackSpeed(speed: Float) {}
    fun release()
}
