package com.linguareader.shared

import com.linguareader.core.alignment.GapFiller
import com.linguareader.core.audio.FileSpeechSynthesizer
import com.linguareader.core.audio.SpokenMark
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.audio.WavSilenceTrim
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.TtsEngineInfo
import com.linguareader.core.engine.VoiceGender
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.platform.formatFixed
import com.linguareader.engine.supertonic.WavWriter
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path
import platform.AVFAudio.AVAudioBuffer
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisMarker
import platform.AVFAudio.AVSpeechSynthesisMarkerMark
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
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRange
import platform.darwin.NSObject

/**
 * Voz del sistema en iOS (`AVSpeechSynthesizer`).
 *
 * - Como [StreamingSpeechEngine]: reproduce directamente y reenvía `willSpeakRangeOfSpeechString` como
 *   [SpeechEvent.WordRange] (el equivalente de `onRangeStart` en Android).
 * - Como [FileSpeechSynthesizer] (lo que usa el lector): `write(_:toBufferCallback:toMarkerCallback:)` (iOS 17)
 *   genera cada frase a un WAV con las marcas de palabra en muestras, que se convierten en [SpokenMark]. Así lee con
 *   el mismo flujo que Android: frase a frase a la caché de disco, con precarga y sin regenerar lo ya leído.
 *
 * Las voces mejoradas y premium se descargan desde Ajustes de iOS y son offline.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSpeechEngine : StreamingSpeechEngine, FileSpeechSynthesizer {

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
    private val fileMutex = Mutex()

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

    private fun activateSession() {
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        }
    }

    /** Voz pedida o la mejor del idioma (exacta antes que genérica, mejor calidad antes). */
    private fun resolveVoice(languageTag: String, voiceId: String?): AVSpeechSynthesisVoice? {
        voiceId?.let { AVSpeechSynthesisVoice.voiceWithIdentifier(it) }?.let { return it }
        val primary = languageTag.substringBefore('-').lowercase()
        val installed = AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>()
            .filter { it.language.lowercase().substringBefore('-') == primary }
        val exact = installed.filter { it.language.equals(languageTag, ignoreCase = true) }
        return (exact.ifEmpty { installed }).maxByOrNull { qualityOf(it) }
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(primary)
    }

    override suspend fun prepare(languageTag: String, voiceId: String?): EngineReadiness {
        activateSession()
        val chosen = resolveVoice(languageTag, voiceId)
            ?: return EngineReadiness.Unavailable(EngineUnavailableReason.LANGUAGE_NOT_SUPPORTED, languageTag)
        voice = chosen
        return EngineReadiness.Ready
    }

    override suspend fun availability(languageTag: String, voiceId: String?): EngineReadiness =
        if (resolveVoice(languageTag, voiceId) != null) EngineReadiness.Ready
        else EngineReadiness.Unavailable(EngineUnavailableReason.LANGUAGE_NOT_SUPPORTED, languageTag)

    private fun utterance(text: String, voice: AVSpeechSynthesisVoice?, speed: Float, pitch: Float): AVSpeechUtterance =
        AVSpeechUtterance(string = text).apply {
            this.voice = voice
            rate = (AVSpeechUtteranceDefaultSpeechRate * speed).coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
            pitchMultiplier = pitch.coerceIn(0.5f, 2.0f)
        }

    override fun enqueue(utteranceId: String, text: String, speed: Float, flush: Boolean) {
        if (flush) stop()
        val u = utterance(text, voice, speed, 1.0f)
        synchronized(lock) {
            ids[u] = utteranceId
            lastUtterance = u
        }
        synthesizer.speakUtterance(u)
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

    override fun release() = Unit

    /** Versión del motor y del sistema: si iOS cambia de versión (voces nuevas), el audio en caché se regenera. */
    override val modelVersion: String
        get() = "apple@${NSProcessInfo.processInfo.operatingSystemVersionString.filter { it.isDigit() || it == '.' }}-marks1"

    /**
     * Genera la frase a [target] con `write(_:toBufferCallback:toMarkerCallback:)`: PCM 16 bits mono en WAV, recorte
     * de silencios como en Android y marcas de palabra en ms. Una sola síntesis a la vez (cola del sistema).
     */
    override suspend fun synthesizeTo(request: SpeechRequest, target: Path): SynthesisOutcome = fileMutex.withLock {
        val chosen = resolveVoice(request.languageTag, request.voiceId)
            ?: return SynthesisOutcome.Failed(SpeechResult.Failure(EngineUnavailableReason.LANGUAGE_NOT_SUPPORTED, "Idioma no disponible"))
        val pitch = request.parameters[PITCH_PARAM]?.toFloatOrNull() ?: 1.0f
        val job = FileJob()
        val writer = AVSpeechSynthesizer()
        withContext(Dispatchers.Main) {
            writer.writeUtterance(
                utterance(request.text, chosen, request.speed, pitch),
                toBufferCallback = { buffer: AVAudioBuffer? -> job.onBuffer(buffer) },
                toMarkerCallback = { markers: List<*>? -> job.onMarkers(markers) },
            )
        }
        val ok = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { job.done.await() } ?: false
        if (!ok || job.samples.isEmpty() || job.sampleRate <= 0) {
            return SynthesisOutcome.Failed(SpeechResult.Failure(EngineUnavailableReason.SYNTHESIS_ERROR, "Fallo al sintetizar"))
        }
        WavWriter.write(target, job.samples.toFloatArray(), job.sampleRate)
        val leadMs = WavSilenceTrim.trim(target) ?: 0L
        val durationMs = GapFiller.wavDurationMs(target) ?: 0L
        val rawDurationMs = job.samples.size * 1000L / job.sampleRate
        val marks = job.marks(request.text.length, rawDurationMs).map { (start, end, ms) -> SpokenMark(start, end, (ms - leadMs).coerceAtLeast(0L)) }
        SynthesisOutcome.Written(durationMs, marks.takeIf { it.isNotEmpty() })
    }

    /** Recibe los bloques de audio y las marcas de una síntesis a fichero. */
    private class FileJob {
        val done = CompletableDeferred<Boolean>()
        val samples = ArrayList<Float>(1 shl 16)
        var sampleRate = 0
        var bytesPerFrame = 4
        private val rawMarks = ArrayList<Triple<Int, Int, Long>>() // (inicio, fin, byteSampleOffset)

        fun onBuffer(buffer: AVAudioBuffer?) {
            val pcm = buffer as? AVAudioPCMBuffer
            if (pcm == null) { done.complete(samples.isNotEmpty()); return }
            val frames = pcm.frameLength.toInt()
            if (frames == 0) { done.complete(samples.isNotEmpty()); return }
            val format = pcm.format
            if (sampleRate == 0) {
                sampleRate = format.sampleRate.toInt()
                bytesPerFrame = format.streamDescription?.pointed?.mBytesPerFrame?.toInt()?.takeIf { it > 0 } ?: 4
            }
            val channels = format.channelCount.toInt().coerceAtLeast(1)
            val floats = pcm.floatChannelData
            val shorts = pcm.int16ChannelData
            when {
                floats != null -> {
                    val channel = floats[0]!!
                    for (i in 0 until frames) samples.add(channel[i])
                }
                shorts != null -> {
                    val channel = shorts[0]!!
                    for (i in 0 until frames) samples.add(channel[i] / 32768f)
                }
                else -> Unit
            }
            if (channels > 1 && floats == null && shorts == null) Unit
        }

        fun onMarkers(markers: List<*>?) {
            val all = markers?.filterIsInstance<AVSpeechSynthesisMarker>().orEmpty()
            if (rawMarks.isEmpty()) NSLog("AppleSpeechEngine: %d markers (%d items), first mark=%@", all.size, markers?.size ?: 0, all.firstOrNull()?.mark?.toString() ?: "-")
            all.forEach { m ->
                if (m.mark != AVSpeechSynthesisMarkerMark.AVSpeechSynthesisMarkerMarkWord) return@forEach
                val (start, end) = m.textRange.useContents { location.toInt() to (location + length).toInt() }
                rawMarks.add(Triple(start, end, m.byteSampleOffset.toLong()))
            }
        }

        /**
         * Marcas en ms. `byteSampleOffset` no documenta su unidad: se interpreta como muestras si así cabe en la
         * duración del audio y como bytes (muestras × bytes por frame) en caso contrario.
         */
        fun marks(textLength: Int, durationMs: Long): List<Triple<Int, Int, Long>> {
            if (rawMarks.isEmpty() || sampleRate <= 0) return emptyList()
            val valid = rawMarks.filter { (s, e, _) -> s in 0 until e && e <= textLength }.sortedBy { it.third }
            if (valid.isEmpty()) return emptyList()
            val maxOffset = valid.maxOf { it.third }
            val asFramesMs = maxOffset * 1000L / sampleRate
            val divisor = if (asFramesMs <= durationMs * 105 / 100) 1L else bytesPerFrame.toLong()
            return valid.map { (s, e, offset) -> Triple(s, e, offset / divisor * 1000L / sampleRate) }
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
                    quality = qualityOf(v),
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

    private fun qualityOf(v: AVSpeechSynthesisVoice): Int = when (v.quality) {
        AVSpeechSynthesisVoiceQualityPremium -> 500
        AVSpeechSynthesisVoiceQualityEnhanced -> 400
        else -> 300
    }

    companion object {
        private const val SYNTH_TIMEOUT_MS = 60_000L
        private const val PITCH_PARAM = "pitch"

        /** Parámetros de petición (y de clave de caché) para un tono; vacío si es el normal. */
        fun pitchParameters(pitch: Float): Map<String, String> =
            if (pitch == 1.0f) emptyMap() else mapOf(PITCH_PARAM to formatFixed(pitch.toDouble(), 2))
    }
}
