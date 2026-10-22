package com.linguareader.core.audio

import com.linguareader.core.content.Hashing
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.platform.formatFixed
import com.linguareader.core.text.Tokenizer

/**
 * Clave determinista para audio sintetizado: hash del texto normalizado + idioma + motor/versión
 * + voz + velocidad + parámetros. Cualquier cambio en estos datos produce otra clave, de modo
 * que una alineación persistida nunca se reutiliza con un audio distinto.
 */
object AudioCacheKey {

    fun of(request: SpeechRequest, engineId: String, engineVersion: String): String {
        val canonical = buildString {
            append("v1\n")
            append("text=").append(Tokenizer.normalize(request.text)).append('\n')
            append("lang=").append(request.languageTag.lowercase()).append('\n')
            append("engine=").append(engineId).append('@').append(engineVersion).append('\n')
            append("voice=").append(request.voiceId ?: "default").append('\n')
            // Igual que `"%.2f".format(Locale.ROOT, speed)`: la clave no cambia entre plataformas ni versiones.
            append("speed=").append(formatFixed(request.speed.toDouble(), 2)).append('\n')
            request.parameters.entries.sortedBy { it.key }.forEach { (k, v) -> append("p.").append(k).append('=').append(v).append('\n') }
        }
        return Hashing.sha256Hex(canonical)
    }
}
