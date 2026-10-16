package com.linguareader.engine.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.linguareader.core.reader.AudioFocusGate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Pide foco de audio al reproducir y pausa la lectura si otra app lo toma. */
@Singleton
class AndroidAudioFocusGate @Inject constructor(
    @ApplicationContext context: Context,
) : AudioFocusGate {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var current: AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        abandon()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                // Solo pérdida permanente: algunos motores TTS piden foco transitorio por su cuenta y
                // pausar en LOSS_TRANSIENT cortaría nuestra propia lectura. Revisar con MediaSession (Fase 2).
                if (change == AudioManager.AUDIOFOCUS_LOSS) onLoss()
            }
            .build()
        current = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        current?.let { audioManager.abandonAudioFocusRequest(it) }
        current = null
    }
}
