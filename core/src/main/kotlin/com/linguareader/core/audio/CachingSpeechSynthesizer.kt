package com.linguareader.core.audio

import com.linguareader.core.content.Hashing
import com.linguareader.core.engine.AudioAsset
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.SpeechSynthesizer
import com.linguareader.core.engine.TtsEngineInfo
import com.linguareader.core.engine.VoiceInfo
import java.io.File

/**
 * Motor que sabe sintetizar directamente a un fichero concreto (lo usa [CachingSpeechSynthesizer]).
 * [modelVersion] debe cambiar con cualquier cambio de pesos/configuración: forma parte de la clave.
 */
interface FileSpeechSynthesizer {
    val info: TtsEngineInfo
    val modelVersion: String
    suspend fun synthesizeTo(request: SpeechRequest, target: File): SynthesisOutcome
    suspend fun listVoices(languageTag: String): List<VoiceInfo>
    suspend fun availability(languageTag: String, voiceId: String?): EngineReadiness
    fun release()
}

sealed interface SynthesisOutcome {
    data class Written(val durationMs: Long) : SynthesisOutcome
    data class Failed(val failure: SpeechResult.Failure) : SynthesisOutcome
}

/**
 * Decorador genérico: resuelve primero en [cache] y solo sintetiza si no hay acierto. Sirve para
 * cualquier motor futuro que genere ficheros.
 */
class CachingSpeechSynthesizer(
    private val delegate: FileSpeechSynthesizer,
    private val cache: FileAudioCache,
    private val onCacheHit: (SpeechRequest) -> Unit = {},
) : SpeechSynthesizer {

    override val id: String get() = delegate.info.id
    override val info: TtsEngineInfo get() = delegate.info

    fun keyFor(request: SpeechRequest): String = AudioCacheKey.of(request, delegate.info.id, delegate.modelVersion)

    override suspend fun synthesize(request: SpeechRequest): SpeechResult {
        val key = keyFor(request)
        cache.get(key)?.let { hit ->
            onCacheHit(request)
            return SpeechResult.Success(AudioAsset(hit.path, "audio/wav", durationMs = null, sha256 = null), delegate.modelVersion)
        }
        val tmp = cache.tempFileFor(key)
        try {
            return when (val outcome = delegate.synthesizeTo(request, tmp)) {
                is SynthesisOutcome.Failed -> outcome.failure
                is SynthesisOutcome.Written -> {
                    val file = cache.commit(key, tmp)
                    SpeechResult.Success(
                        AudioAsset(file.path, "audio/wav", outcome.durationMs, Hashing.sha256Hex(file.readBytes())),
                        delegate.modelVersion,
                    )
                }
            }
        } finally {
            cache.discard(tmp) // no-op si ya se publicó (renombrado)
        }
    }

    override suspend fun listVoices(languageTag: String) = delegate.listVoices(languageTag)
    override suspend fun availability(languageTag: String, voiceId: String?) = delegate.availability(languageTag, voiceId)
    override fun release() = delegate.release()
}
