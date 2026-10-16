package com.linguareader.engine.audio

import android.content.Context
import com.linguareader.core.reader.ClipEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reproduce una frase pregenerada corta (p. ej. «Preparando la lectura…») y suspende hasta que
 * termina. Sin foco de audio propio: es solo un aviso mientras se prepara la lectura real.
 */
@Singleton
class CuePlayer @Inject constructor(@param:ApplicationContext private val context: Context) {

    private var player: Media3ClipPlayer? = null

    /** Duración aproximada de un WAV PCM16 mono de 44,1 kHz (como los de Supertonic). */
    private fun durationMs(file: File) = ((file.length() - 44) * 1000 / (2 * 44_100)).coerceAtLeast(0)

    suspend fun play(file: File) {
        val p = player ?: Media3ClipPlayer(context, handleAudioFocus = false).also { player = it }
        p.clear()
        p.enqueue(0, file.path)
        p.play()
        // Espera al fin real de la cola; el tiempo máximo evita bloquear si el reproductor falla.
        withTimeoutOrNull(durationMs(file) + 1_500) { p.events.first { it is ClipEvent.QueueEnded || it is ClipEvent.Error } }
    }

    fun stop() {
        player?.clear()
    }

    fun release() {
        player?.release()
        player = null
    }
}
