package com.linguareader.engine.tts

import okio.Path
import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.os.Build
import android.provider.Settings
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
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.platform.formatFixed
import com.linguareader.di.EngineSettingsHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Adaptador del TTS del sistema Android.
 *
 * - Como [StreamingSpeechEngine]: reproduce directamente y reenvía `onRangeStart` como
 *   [SpeechEvent.WordRange]. Que el motor instalado emita esos eventos depende del motor/voz;
 *   si no lo hace, el lector resalta por frase.
 * - Como [FileSpeechSynthesizer] (lo que usa el lector): `synthesizeToFile` frase a frase a la caché de disco, con las
 *   marcas `onRangeStart` convertidas en tiempos por palabra. Así lee con el mismo flujo que Supertonic: la frase
 *   siguiente se genera mientras suena la actual (sin silencios largos entre frases) y lo ya leído no se regenera.
 *
 * Prefiere voces que no requieren red para mantener el modo offline.
 */
@Singleton
class AndroidTtsEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: EngineSettingsHolder,
) : StreamingSpeechEngine, FileSpeechSynthesizer {

    override val id: String = "android-tts"
    override val supportsSpeed: Boolean = true
    override val info = TtsEngineInfo(
        id = id,
        displayName = "Voz del sistema (Android TTS)",
        streaming = true,
        providesWordTimings = true, // solo si el motor instalado emite onRangeStart; si no, resaltado por frase
        requiresModelDownload = false,
        offline = true,
    )

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val initMutex = Mutex()
    @Volatile private var tts: TextToSpeech? = null
    /** Síntesis a fichero en curso: fin, frecuencia del audio y marcas (inicio, fin, frame) que van llegando. */
    private class FileJob {
        val done = CompletableDeferred<Boolean>()
        @Volatile var sampleRate = 0
        val marks: MutableList<Triple<Int, Int, Int>> = java.util.Collections.synchronizedList(mutableListOf())
    }
    private val fileJobs = ConcurrentHashMap<String, FileJob>()
    private val fileMutex = Mutex()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            if (!fileJobs.containsKey(utteranceId)) _events.tryEmit(SpeechEvent.Started(utteranceId))
        }

        override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
            fileJobs[utteranceId]?.sampleRate = sampleRateInHz
        }

        override fun onDone(utteranceId: String) {
            val job = fileJobs[utteranceId]
            if (job != null) job.done.complete(true) else _events.tryEmit(SpeechEvent.Done(utteranceId))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String, errorCode: Int) {
            val job = fileJobs[utteranceId]
            if (job != null) job.done.complete(false) else _events.tryEmit(SpeechEvent.Error(utteranceId, errorCode))
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            val job = fileJobs[utteranceId]
            if (job != null) job.done.complete(false) else _events.tryEmit(SpeechEvent.Stopped(utteranceId))
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            val job = fileJobs[utteranceId]
            if (job != null) job.marks += Triple(start, end, frame)
            else _events.tryEmit(SpeechEvent.WordRange(utteranceId, start, end))
        }
    }

    /** Paquete con el que se creó [tts] (null = el predeterminado del sistema). */
    @Volatile private var ttsPackage: String? = null

    private suspend fun engine(): TextToSpeech? = initMutex.withLock {
        val wanted = settings.systemTtsEngine
        val current = tts
        if (current != null && ttsPackage == wanted) return@withLock current
        if (current != null) {
            // El usuario cambió de motor en los ajustes: se cierra el anterior y se crea el nuevo.
            withContext(Dispatchers.Main) { runCatching { current.stop(); current.shutdown() } }
            tts = null
        }
        withContext(Dispatchers.Main) { create(wanted) }?.also { tts = it; ttsPackage = wanted }
    }

    private suspend fun create(enginePackage: String?): TextToSpeech? = suspendCancellableCoroutine { cont ->
        var created: TextToSpeech? = null
        val onInit = TextToSpeech.OnInitListener { status ->
            val instance = created
            if (status == TextToSpeech.SUCCESS && instance != null) {
                instance.setOnUtteranceProgressListener(listener)
                instance.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                if (cont.isActive) cont.resume(instance)
            } else {
                Log.w(TAG, "TextToSpeech no disponible (status=$status, motor=${enginePackage ?: "predeterminado"})")
                instance?.shutdown()
                if (cont.isActive) cont.resume(null)
            }
        }
        created = if (enginePackage == null) TextToSpeech(context, onInit) else TextToSpeech(context, onInit, enginePackage)
    }

    /** Motores de texto a voz instalados (paquete y nombre), con el predeterminado del sistema primero. */
    suspend fun engines(): List<SystemTtsEngine> {
        val t = engine() ?: return emptyList()
        return withContext(Dispatchers.Main) {
            val default = t.defaultEngine
            t.engines.orEmpty().map { SystemTtsEngine(it.name, it.label, it.name == default) }.sortedByDescending { it.isDefault }
        }
    }

    /** Reproduce [text] con la voz [voiceId] (o la mejor del idioma) para probarla; corta lo que estuviera sonando. */
    suspend fun preview(languageTag: String, voiceId: String?, text: String, speed: Float, pitch: Float): Boolean {
        if (prepare(languageTag, voiceId) !is EngineReadiness.Ready) return false
        val t = tts ?: return false
        return withContext(Dispatchers.Main) {
            t.setSpeechRate(speed)
            t.setPitch(pitch)
            val id = "preview|${System.nanoTime()}"
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
            t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS
        }
    }

    override suspend fun prepare(languageTag: String, voiceId: String?): EngineReadiness {
        val t = engine() ?: return EngineReadiness.Unavailable(EngineUnavailableReason.ENGINE_NOT_INSTALLED, languageTag)
        val locale = Locale.forLanguageTag(languageTag)
        return withContext(Dispatchers.Main) {
            when (t.isLanguageAvailable(locale)) {
                TextToSpeech.LANG_MISSING_DATA ->
                    EngineReadiness.Unavailable(EngineUnavailableReason.LANGUAGE_DATA_MISSING, languageTag)
                TextToSpeech.LANG_NOT_SUPPORTED ->
                    EngineReadiness.Unavailable(EngineUnavailableReason.LANGUAGE_NOT_SUPPORTED, languageTag)
                else -> {
                    val voice = voiceId?.let { id -> installedVoices(t, languageTag).firstOrNull { it.name == id } }
                        ?: bestOfflineVoice(t, languageTag)
                    val result = if (voice != null) t.setVoice(voice) else t.setLanguage(locale)
                    if (result == TextToSpeech.ERROR || result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        EngineReadiness.Unavailable(EngineUnavailableReason.LANGUAGE_DATA_MISSING, languageTag)
                    } else {
                        EngineReadiness.Ready
                    }
                }
            }
        }
    }

    override fun enqueue(utteranceId: String, text: String, speed: Float, flush: Boolean) {
        val t = tts
        if (t == null) {
            _events.tryEmit(SpeechEvent.Error(utteranceId, TextToSpeech.ERROR_NOT_INSTALLED_YET))
            return
        }
        t.setSpeechRate(speed)
        t.setPitch(settings.ttsPitch)
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
        val result = t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) _events.tryEmit(SpeechEvent.Error(utteranceId, result))
    }

    override fun enqueueSilence(utteranceId: String, durationMs: Long) {
        if (durationMs > 0) tts?.playSilentUtterance(durationMs, TextToSpeech.QUEUE_ADD, utteranceId)
    }

    override fun stop() {
        tts?.stop()
    }

    override suspend fun listVoices(languageTag: String): List<VoiceInfo> {
        val t = engine() ?: return emptyList()
        return withContext(Dispatchers.Main) {
            val voices = installedVoices(t, languageTag)
                .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.name })
            // Numeración por idioma y género: «Voz femenina 1, 2…» en vez de nombres como en-us-x-sfg#female_2-local.
            val counters = HashMap<String, Int>()
            voices.map { v ->
                val gender = genderOf(v.name)
                val named = isNamed(v.name)
                val key = "${v.locale.toLanguageTag()}|$gender"
                val number = (counters[key] ?: 0) + 1
                counters[key] = number
                VoiceInfo(
                    id = v.name,
                    languageTag = v.locale.toLanguageTag(),
                    displayName = if (named) v.name.replaceFirstChar { it.titlecase(Locale.ROOT) } else v.name,
                    requiresNetwork = v.isNetworkConnectionRequired,
                    quality = v.quality,
                    gender = gender,
                    number = number,
                    named = named,
                )
            }
        }
    }

    /** Motor del sistema y su versión: si se actualiza (voces nuevas), el audio en caché se regenera. */
    override val modelVersion: String
        get() {
            val pkg = settings.systemTtsEngine
                ?: tts?.defaultEngine
                ?: Settings.Secure.getString(context.contentResolver, "tts_default_synth")
                ?: "default"
            val code = runCatching {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            }.getOrDefault(0L)
            return "$pkg@$code-marks2"
        }

    override suspend fun availability(languageTag: String, voiceId: String?): EngineReadiness = prepare(languageTag, voiceId)

    override fun release() = Unit // el servicio del sistema es ligero; se mantiene para no pagar su arranque

    /**
     * Genera la frase a [target] con `synthesizeToFile` y recoge, mientras se genera, las marcas `onRangeStart` con su
     * posición en el audio (frames → ms). Recorta el silencio inicial y final como Supertonic y desplaza las marcas.
     * Una sola síntesis a la vez: el motor del sistema es una cola y la voz se fija por petición.
     */
    override suspend fun synthesizeTo(request: SpeechRequest, target: Path): SynthesisOutcome = fileMutex.withLock {
        val t = engine() ?: return SynthesisOutcome.Failed(SpeechResult.Failure(EngineUnavailableReason.ENGINE_NOT_INSTALLED, "Sin motor TTS"))
        val readiness = prepare(request.languageTag, request.voiceId)
        if (readiness is EngineReadiness.Unavailable) {
            return SynthesisOutcome.Failed(SpeechResult.Failure(readiness.reason, "Idioma no disponible"))
        }
        val utteranceId = "file|${System.nanoTime()}"
        val job = FileJob()
        fileJobs[utteranceId] = job
        try {
            t.setSpeechRate(request.speed)
            t.setPitch(request.parameters[PITCH_PARAM]?.toFloatOrNull() ?: 1.0f)
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
            val t0 = SystemClock.elapsedRealtime()
            val started = t.synthesizeToFile(request.text, params, target.toFile(), utteranceId) == TextToSpeech.SUCCESS
            val ok = started && (withTimeoutOrNull(SYNTH_TIMEOUT_MS) { job.done.await() } ?: false)
            val synthMs = SystemClock.elapsedRealtime() - t0
            if (!ok || target.toFile().length() <= WAV_HEADER_BYTES) {
                return SynthesisOutcome.Failed(SpeechResult.Failure(EngineUnavailableReason.SYNTHESIS_ERROR, "Fallo al sintetizar"))
            }
            val leadMs = WavSilenceTrim.trim(target) ?: 0L
            val rate = job.sampleRate
            val raw = synchronized(job.marks) { job.marks.toList() }
            val marks = if (rate <= 0) null else fileMarks(raw, request.text.length).map { (start, end, frame) ->
                SpokenMark(start, end, (frame * 1000L / rate - leadMs).coerceAtLeast(0L))
            }
            val durationMs = GapFiller.wavDurationMs(target) ?: 0L
            Log.i(TAG, "TTS: ${request.text.length} car. → $durationMs ms de audio en $synthMs ms (recorte ${SystemClock.elapsedRealtime() - t0 - synthMs} ms, ${marks?.size ?: 0} marcas)")
            SynthesisOutcome.Written(durationMs, marks)
        } catch (e: CancellationException) {
            t.stop() // lo que queda en la cola es solo esta petición (síntesis de una en una)
            throw e
        } finally {
            fileJobs.remove(utteranceId)
        }
    }

    /**
     * Marcas recibidas al generar a fichero como (inicio, fin, frame). En el camino de `synthesizeToFile` Android las
     * entrega desordenadas en muchas versiones (frame, inicio, fin): se detecta por los valores y se reordena.
     */
    private fun fileMarks(raw: List<Triple<Int, Int, Int>>, textLength: Int): List<Triple<Int, Int, Int>> {
        fun inText(start: Int, end: Int) = start in 0 until end && end <= textLength
        val asDocumented = raw.all { (a, b, _) -> inText(a, b) }
        val swapped = raw.all { (_, b, c) -> inText(b, c) }
        val useSwapped = swapped && (!asDocumented || raw.maxOf { it.first } > raw.maxOf { it.third })
        return when {
            raw.isEmpty() -> emptyList()
            useSwapped -> raw.map { (frame, start, end) -> Triple(start, end, frame) }
            asDocumented -> raw
            else -> emptyList() // ni uno ni otro: sin marcas, mejor resaltar por frase que inventar
        }
    }

    private fun installedVoices(t: TextToSpeech, languageTag: String): List<Voice> =
        runCatching { t.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { LanguageTags.sameLanguage(it.locale.toLanguageTag(), languageTag) }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }

    private fun bestOfflineVoice(t: TextToSpeech, languageTag: String): Voice? {
        val candidates = installedVoices(t, languageTag).filter { !it.isNetworkConnectionRequired }
        val exact = candidates.filter { it.locale.toLanguageTag().equals(languageTag, ignoreCase = true) }
        return (exact.ifEmpty { candidates }).maxByOrNull { it.quality }
    }

    companion object {
        const val TAG = "AndroidTtsEngine"
        private const val SYNTH_TIMEOUT_MS = 60_000L
        private const val WAV_HEADER_BYTES = 44L
        private const val PITCH_PARAM = "pitch"

        /** Parámetros de petición (y de clave de caché) para un tono; vacío si es el normal. */
        fun pitchParameters(pitch: Float): Map<String, String> =
            if (pitch == 1.0f) emptyMap() else mapOf(PITCH_PARAM to formatFixed(pitch.toDouble(), 2))

        /** Género según el nombre técnico de la voz (Google: «…#female_2-local», «…#male_1-local»). */
        fun genderOf(name: String): VoiceGender {
            val n = name.lowercase(Locale.ROOT)
            return when {
                "female" in n || Regex("[#_-]f(em)?[_\\d-]").containsMatchIn(n) -> VoiceGender.FEMALE
                "male" in n || Regex("[#_-]m(asc)?[_\\d-]").containsMatchIn(n) -> VoiceGender.MALE
                else -> VoiceGender.UNKNOWN
            }
        }

        /** Nombre propio (RHVoice: «Alan», «Slt») frente a identificador técnico (Google: «en-us-x-iom-local»). */
        fun isNamed(name: String): Boolean =
            '#' !in name && !Regex("^[a-z]{2,3}[-_][a-zA-Z]{2,4}([-_]|$)").containsMatchIn(name) && name.length <= 24
    }
}

/** Motor de texto a voz instalado en el sistema. */
data class SystemTtsEngine(val packageName: String, val label: String, val isDefault: Boolean)
