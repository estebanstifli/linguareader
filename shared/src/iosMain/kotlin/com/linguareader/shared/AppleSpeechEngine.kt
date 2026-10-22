package com.linguareader.shared

import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.TtsEngineInfo
import com.linguareader.core.engine.VoiceGender
import com.linguareader.core.engine.VoiceInfo
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesisVoiceGender
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityEnhanced
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityPremium
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import platform.AVFAudio.setActive
import platform.Foundation.NSRange
import platform.darwin.NSObject

/**
 * Voz del sistema en iOS (`AVSpeechSynthesizer`) como [StreamingSpeechEngine]: reproduce directamente y reenvía
 * `willSpeakRangeOfSpeechString` como [SpeechEvent.WordRange] (el equivalente de `onRangeStart` en Android), así
 * el `StreamingReaderAudioController` de `core` resalta palabra a palabra sin cambios. Las voces mejoradas y
 * premium se descargan desde Ajustes de iOS y son offline.
 *
 * Pendiente (spike de la Fase 1): sintetizar a fichero con `write(_:toBufferCallback:toMarkerCallback:)` para
 * usar el flujo renderizado con caché, como hace Android.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSpeechEngine : StreamingSpeechEngine {

    override val id = "apple-tts"
    override val info = TtsEngineInfo(
        id = id,
        displayName = "Apple voices",
        streaming = true,
        providesWordTimings = true,
        requiresModelDownload = false,
        offline = true,
    )
    override val supportsSpeed = true

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val lock = SynchronizedObject()
    private val ids = HashMap<AVSpeechUtterance, String>()
    private var voice: AVSpeechSynthesisVoice? = null
    private var lastUtterance: AVSpeechUtterance? = null

    private val synthesizer = AVSpeechSynthesizer()

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) {
            idOf(didStartSpeechUtterance)?.let { _events.tryEmit(SpeechEvent.Started(it)) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            removeId(didFinishSpeechUtterance)?.let { _events.tryEmit(SpeechEvent.Done(it)) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            removeId(didCancelSpeechUtterance)?.let { _events.tryEmit(SpeechEvent.Stopped(it)) }
        }

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            willSpeakRangeOfSpeechString: CValue<NSRange>,
            utterance: AVSpeechUtterance,
        ) {
            val id = idOf(utterance) ?: return
            val (start, end) = willSpeakRangeOfSpeechString.useContents { location.toInt() to (location + length).toInt() }
            _events.tryEmit(SpeechEvent.WordRange(id, start, end))
        }
    }

    init {
        synthesizer.delegate = delegate
    }

    private fun idOf(utterance: AVSpeechUtterance): String? = synchronized(lock) { ids[utterance] }
    private fun removeId(utterance: AVSpeechUtterance): String? = synchronized(lock) { ids.remove(utterance) }

    override suspend fun prepare(languageTag: String, voiceId: String?): EngineReadiness {
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        }
        val chosen = voiceId?.let { AVSpeechSynthesisVoice.voiceWithIdentifier(it) }
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(languageTag.substringBefore('-'))
            ?: return EngineReadiness.Unavailable(EngineUnavailableReason.LANGUAGE_NOT_SUPPORTED, languageTag)
        voice = chosen
        return EngineReadiness.Ready
    }

    override fun enqueue(utteranceId: String, text: String, speed: Float, flush: Boolean) {
        if (flush) stop()
        val utterance = AVSpeechUtterance(string = text).apply {
            this.voice = this@AppleSpeechEngine.voice
            rate = (AVSpeechUtteranceDefaultSpeechRate * speed).coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
        }
        synchronized(lock) {
            ids[utterance] = utteranceId
            lastUtterance = utterance
        }
        synthesizer.speakUtterance(utterance)
    }

    /** La pausa entre frases se añade como silencio tras el último enunciado encolado. */
    override fun enqueueSilence(utteranceId: String, durationMs: Long) {
        synchronized(lock) { lastUtterance }?.let { it.postUtteranceDelay = durationMs / 1000.0 }
    }

    override fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        synchronized(lock) {
            ids.clear()
            lastUtterance = null
        }
    }

    override suspend fun listVoices(languageTag: String): List<VoiceInfo> {
        val primary = languageTag.substringBefore('-').lowercase()
        return AVSpeechSynthesisVoice.speechVoices()
            .filterIsInstance<AVSpeechSynthesisVoice>()
            .filter { it.language.lowercase().substringBefore('-') == primary }
            .map { v ->
                VoiceInfo(
                    id = v.identifier,
                    languageTag = v.language,
                    displayName = v.name,
                    requiresNetwork = false,
                    quality = when (v.quality) {
                        AVSpeechSynthesisVoiceQualityPremium -> 500
                        AVSpeechSynthesisVoiceQualityEnhanced -> 400
                        else -> 300
                    },
                    gender = when (v.gender) {
                        AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderFemale -> VoiceGender.FEMALE
                        AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderMale -> VoiceGender.MALE
                        else -> VoiceGender.UNKNOWN
                    },
                    named = true,
                )
            }
            .sortedWith(compareByDescending<VoiceInfo> { it.quality }.thenBy { it.displayName })
    }
}
