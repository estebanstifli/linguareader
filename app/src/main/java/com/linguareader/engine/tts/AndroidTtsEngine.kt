package com.linguareader.engine.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.linguareader.core.audio.AudioCacheKey
import com.linguareader.core.content.Hashing
import com.linguareader.core.engine.AudioAsset
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.SpeechSynthesizer
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.model.LanguageTags
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * - Como [SpeechSynthesizer]: `synthesizeToFile` a la caché (base para Fase 3), sin tiempos.
 *
 * Prefiere voces que no requieren red para mantener el modo offline.
 */
@Singleton
class AndroidTtsEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : StreamingSpeechEngine, SpeechSynthesizer {

    override val id: String = "android-tts"
    override val supportsSpeed: Boolean = true

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val initMutex = Mutex()
    @Volatile private var tts: TextToSpeech? = null
    private val pendingFiles = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            if (!pendingFiles.containsKey(utteranceId)) _events.tryEmit(SpeechEvent.Started(utteranceId))
        }

        override fun onDone(utteranceId: String) {
            val pending = pendingFiles.remove(utteranceId)
            if (pending != null) pending.complete(true) else _events.tryEmit(SpeechEvent.Done(utteranceId))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String, errorCode: Int) {
            val pending = pendingFiles.remove(utteranceId)
            if (pending != null) pending.complete(false) else _events.tryEmit(SpeechEvent.Error(utteranceId, errorCode))
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            pendingFiles.remove(utteranceId)?.complete(false)
            _events.tryEmit(SpeechEvent.Stopped(utteranceId))
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            _events.tryEmit(SpeechEvent.WordRange(utteranceId, start, end))
        }
    }

    private suspend fun engine(): TextToSpeech? = initMutex.withLock {
        tts ?: withContext(Dispatchers.Main) { create() }?.also { tts = it }
    }

    private suspend fun create(): TextToSpeech? = suspendCancellableCoroutine { cont ->
        var created: TextToSpeech? = null
        created = TextToSpeech(context) { status ->
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
                Log.w(TAG, "TextToSpeech no disponible (status=$status)")
                instance?.shutdown()
                if (cont.isActive) cont.resume(null)
            }
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
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
        val result = t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) _events.tryEmit(SpeechEvent.Error(utteranceId, result))
    }

    override fun stop() {
        tts?.stop()
    }

    override suspend fun listVoices(languageTag: String): List<VoiceInfo> {
        val t = engine() ?: return emptyList()
        return withContext(Dispatchers.Main) {
            installedVoices(t, languageTag).map { v ->
                VoiceInfo(
                    id = v.name,
                    languageTag = v.locale.toLanguageTag(),
                    displayName = "${v.locale.getDisplayName(Locale.forLanguageTag("es"))} · ${v.name}",
                    requiresNetwork = v.isNetworkConnectionRequired,
                    quality = v.quality,
                )
            }.sortedWith(compareBy<VoiceInfo> { it.requiresNetwork }.thenByDescending { it.quality ?: 0 })
        }
    }

    override suspend fun synthesize(request: SpeechRequest): SpeechResult {
        val t = engine() ?: return SpeechResult.Failure(EngineUnavailableReason.ENGINE_NOT_INSTALLED, "Sin motor TTS")
        val readiness = prepare(request.languageTag, request.voiceId)
        if (readiness is EngineReadiness.Unavailable) return SpeechResult.Failure(readiness.reason, "Idioma no disponible")
        val version = "${t.defaultEngine ?: "unknown"}"
        val key = AudioCacheKey.of(request, id, version)
        val dir = File(context.cacheDir, "tts").apply { mkdirs() }
        val file = File(dir, "$key.wav")
        if (!file.exists()) {
            val utteranceId = "file|$key"
            val done = CompletableDeferred<Boolean>()
            pendingFiles[utteranceId] = done
            t.setSpeechRate(request.speed)
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
            val started = t.synthesizeToFile(request.text, params, file, utteranceId) == TextToSpeech.SUCCESS
            val ok = started && (withTimeoutOrNull(SYNTH_TIMEOUT_MS) { done.await() } ?: false)
            pendingFiles.remove(utteranceId)
            if (!ok) {
                file.delete()
                return SpeechResult.Failure(EngineUnavailableReason.SYNTHESIS_ERROR, "Fallo al sintetizar")
            }
        }
        return SpeechResult.Success(
            audio = AudioAsset(file.absolutePath, "audio/wav", durationMs = null, sha256 = Hashing.sha256Hex(file.readBytes())),
            engineVersion = version,
        )
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

    private companion object {
        const val TAG = "AndroidTtsEngine"
        const val SYNTH_TIMEOUT_MS = 60_000L
    }
}
