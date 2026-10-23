package com.linguareader.shared

import com.linguareader.core.audio.FileSpeechSynthesizer
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.TtsEngineInfo
import com.linguareader.core.engine.VoiceInfo
import okio.Path

/**
 * La misma voz de Apple como motor «renderizado»: frase a frase a la caché de disco (flujo de Android). Tiene su
 * propio id para que el lector pueda elegir entre la lectura en directo (predeterminada) y esta.
 */
class CachedAppleVoice(private val engine: AppleSpeechEngine) : FileSpeechSynthesizer {
    override val info: TtsEngineInfo = engine.info.copy(id = ID, displayName = "Apple voices (cached audio)")
    override val modelVersion: String get() = engine.modelVersion
    override suspend fun synthesizeTo(request: SpeechRequest, target: Path): SynthesisOutcome = engine.synthesizeTo(request, target)
    override suspend fun listVoices(languageTag: String): List<VoiceInfo> = engine.listVoices(languageTag)
    override suspend fun availability(languageTag: String, voiceId: String?): EngineReadiness = engine.availability(languageTag, voiceId)
    override fun release() = engine.release()

    companion object {
        const val ID = "apple-tts-cached"
    }
}
