package com.linguareader.core.audio

import com.linguareader.core.content.Hashing
import com.linguareader.core.engine.SpeechRequest
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
            append("speed=").append("%.2f".format(java.util.Locale.ROOT, request.speed)).append('\n')
            request.parameters.toSortedMap().forEach { (k, v) -> append("p.").append(k).append('=').append(v).append('\n') }
        }
        return Hashing.sha256Hex(canonical)
    }
}
