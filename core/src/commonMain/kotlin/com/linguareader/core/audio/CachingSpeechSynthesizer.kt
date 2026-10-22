package com.linguareader.core.audio

import com.linguareader.core.content.Hashing
import com.linguareader.core.engine.AudioAsset
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.SpeechSynthesizer
import com.linguareader.core.engine.TtsEngineInfo
import com.linguareader.core.engine.VoiceInfo
import okio.Path
import kotlin.time.TimeSource

/**
 * Motor que sabe sintetizar directamente a un fichero concreto (lo usa [CachingSpeechSynthesizer]).
 * [modelVersion] debe cambiar con cualquier cambio de pesos/configuración: forma parte de la clave.
 */
interface FileSpeechSynthesizer {
    val info: TtsEngineInfo
    val modelVersion: String
    suspend fun synthesizeTo(request: SpeechRequest, target: Path): SynthesisOutcome
    suspend fun listVoices(languageTag: String): List<VoiceInfo>
    suspend fun availability(languageTag: String, voiceId: String?): EngineReadiness
    fun release()
}

sealed interface SynthesisOutcome {
    /** [marks]: tiempos por palabra que da el propio motor al generar (offsets sobre el texto pedido), si los da. */
    data class Written(val durationMs: Long, val marks: List<SpokenMark>? = null) : SynthesisOutcome
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
    /** Diagnóstico: una línea por síntesis con los ms del motor y de publicar en la caché. */
    private val log: (String) -> Unit = {},
) : SpeechSynthesizer {

    override val id: String get() = delegate.info.id
    override val info: TtsEngineInfo get() = delegate.info

    fun keyFor(request: SpeechRequest): String = AudioCacheKey.of(request, delegate.info.id, delegate.modelVersion)

    override suspend fun synthesize(request: SpeechRequest): SpeechResult {
        val key = keyFor(request)
        cache.get(key)?.let { hit ->
            onCacheHit(request)
            return SpeechResult.Success(AudioAsset(hit.toString(), "audio/wav", durationMs = null, sha256 = null), delegate.modelVersion)
        }
        val tmp = cache.tempFileFor(key)
        try {
            val clock = TimeSource.Monotonic
            val started = clock.markNow()
            return when (val outcome = delegate.synthesizeTo(request, tmp)) {
                is SynthesisOutcome.Failed -> outcome.failure
                is SynthesisOutcome.Written -> {
                    val synthMs = started.elapsedNow().inWholeMilliseconds
                    val commitStarted = clock.markNow()
                    val file = cache.commit(key, tmp)
                    val commitMs = commitStarted.elapsedNow().inWholeMilliseconds
                    log("${delegate.info.id}: ${request.text.length} car. → ${outcome.durationMs} ms de audio en $synthMs ms; publicar en caché $commitMs ms")
                    outcome.marks?.takeIf { it.isNotEmpty() }?.let { SpokenMarksSidecar.write(cache.fileSystem, file, it) }
                    val bytes = cache.fileSystem.read(file) { readByteArray() }
                    SpeechResult.Success(
                        AudioAsset(file.toString(), "audio/wav", outcome.durationMs, Hashing.sha256Hex(bytes)),
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
