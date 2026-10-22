package com.linguareader.core.reader

import com.linguareader.core.reader.ClipEvent
import com.linguareader.core.reader.ClipPlayer
import com.linguareader.core.reader.ClipPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * [ClipPlayer] que crea el reproductor real la primera vez que hace falta. Crear un reproductor (ExoPlayer, AVAudioEngine) cuesta en el hilo
 * principal y el controlador de lectura se construye al arrancar la app: así el arranque no paga cuatro
 * reproductores que quizá nunca suenen. Las llamadas que no necesitan reproductor (pausar, vaciar...) no lo crean.
 */
class LazyClipPlayer(private val factory: () -> ClipPlayer) : ClipPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _events = MutableSharedFlow<ClipEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ClipEvent> = _events

    private var delegate: ClipPlayer? = null
    private var speed = 1f

    private fun player(): ClipPlayer = delegate ?: factory().also { created ->
        delegate = created
        if (speed != 1f) created.setPlaybackSpeed(speed)
        scope.launch { created.events.collect { _events.emit(it) } }
    }

    override fun enqueue(tag: Int, path: String) = player().enqueue(tag, path)
    override fun enqueueSilence(durationMs: Long) = player().enqueueSilence(durationMs)
    override fun play() = player().play()
    override fun clear() { delegate?.clear() }
    override fun pause() { delegate?.pause() }
    override fun seekToTag(tag: Int): Boolean = delegate?.seekToTag(tag) ?: false
    override fun seekTo(tag: Int, positionMs: Long): Boolean = delegate?.seekTo(tag, positionMs) ?: false
    override fun position(): ClipPosition? = delegate?.position()

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed
        delegate?.setPlaybackSpeed(speed)
    }

    override fun release() {
        delegate?.release()
        delegate = null
    }
}
