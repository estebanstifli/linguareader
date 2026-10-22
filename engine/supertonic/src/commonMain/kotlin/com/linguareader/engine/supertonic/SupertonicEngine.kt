package com.linguareader.engine.supertonic

import okio.Path
import com.linguareader.core.audio.FileSpeechSynthesizer
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.TtsEngineInfo
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.model.LanguageTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.linguareader.core.platform.availableProcessors
import com.linguareader.core.platform.formatFixed
import com.linguareader.engine.onnx.OnnxRuntime
import com.linguareader.engine.onnx.platformOnnxRuntime
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import okio.FileSystem
import kotlin.time.TimeSource
import kotlin.coroutines.coroutineContext

/** Memoria aproximada del proceso. En Android: PSS (incluye la memoria nativa de ONNX Runtime). */
fun interface MemoryProbe {
    fun usedBytes(): Long?

    companion object {
        /** Sin medición (plataformas sin sonda; en JVM ver `JvmMemoryProbes.kt`). */
        val NONE = MemoryProbe { null }
    }
}

data class LoadMetrics(val loadMs: Long, val verifyMs: Long, val memoryBeforeMb: Long?, val memoryAfterMb: Long?, val threads: Int)

data class SynthesisMetrics(
    val textChars: Int,
    val language: String,
    val voice: String,
    val steps: Int,
    val speed: Float,
    val chunks: Int,
    /** Tiempo de carga del modelo si esta síntesis tuvo que cargarlo; null si ya estaba cargado. */
    val loadMs: Long?,
    val synthMs: Long,
    val audioMs: Long,
    val memoryMb: Long?,
) {
    /** Real-time factor: tiempo de síntesis / duración del audio (< 1 = más rápido que tiempo real). */
    val rtf: Double get() = if (audioMs == 0L) 0.0 else synthMs.toDouble() / audioMs
}

data class SupertonicStats(
    val loaded: Boolean = false,
    val lastLoad: LoadMetrics? = null,
    val lastSynthesis: SynthesisMetrics? = null,
    val history: List<SynthesisMetrics> = emptyList(),
    val lastError: String? = null,
)

/**
 * Motor Supertonic 3 local (ONNX Runtime en CPU, sin red).
 *
 * - Toda la inferencia ocurre en un único hilo dedicado: nunca en el hilo principal.
 * - El modelo (~398 MB) se carga bajo demanda y se libera tras [idleReleaseMs] sin uso,
 *   o al llamar a [release] (p. ej. al cambiar de motor o con poca memoria).
 * - No proporciona tiempos por palabra ([TtsEngineInfo.providesWordTimings] = false).
 */
class SupertonicEngine(
    private val modelDir: () -> Path,
    private val steps: () -> Int = { DEFAULT_STEPS },
    private val memory: MemoryProbe = MemoryProbe.NONE,
    private val log: (String) -> Unit = {},
    private val idleReleaseMs: Long = 120_000,
    private val intraOpThreads: Int = defaultThreads(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val onnx: () -> OnnxRuntime = ::platformOnnxRuntime,
) : FileSpeechSynthesizer {

    override val info = TtsEngineInfo(
        id = SupertonicModel.ENGINE_ID,
        displayName = SupertonicModel.DISPLAY_NAME,
        streaming = false,
        providesWordTimings = false,
        requiresModelDownload = true,
        offline = true,
        languages = SupertonicModel.LANGUAGES,
    )
    override val modelVersion: String = SupertonicModel.MODEL_VERSION

    @OptIn(DelicateCoroutinesApi::class)
    private val dispatcher: CloseableCoroutineDispatcher = newSingleThreadContext("supertonic-tts")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var runtime: SupertonicRuntime? = null
    private var idleJob: Job? = null

    private val _stats = MutableStateFlow(SupertonicStats())
    val stats: StateFlow<SupertonicStats> = _stats.asStateFlow()

    fun isInstalled(): Boolean = SupertonicModelDir(modelDir(), fileSystem).isComplete()

    override suspend fun availability(languageTag: String, voiceId: String?): EngineReadiness {
        val lang = LanguageTags.primary(languageTag)
        return when {
            !isInstalled() -> EngineReadiness.Unavailable(EngineUnavailableReason.MODEL_NOT_INSTALLED, languageTag)
            lang !in SupertonicModel.LANGUAGES -> EngineReadiness.Unavailable(EngineUnavailableReason.LANGUAGE_NOT_SUPPORTED, languageTag)
            voiceId != null && voiceId !in SupertonicModel.VOICES -> EngineReadiness.Unavailable(EngineUnavailableReason.VOICE_NOT_FOUND, languageTag)
            else -> EngineReadiness.Ready
        }
    }

    override suspend fun listVoices(languageTag: String): List<VoiceInfo> =
        if (LanguageTags.primary(languageTag) !in SupertonicModel.LANGUAGES) emptyList()
        else SupertonicModel.VOICES.map { v ->
            VoiceInfo(
                id = v,
                languageTag = languageTag,
                displayName = (if (v.startsWith("F")) "Voz femenina " else "Voz masculina ") + v.drop(1) + " (Supertonic)",
                requiresNetwork = false,
                quality = null,
            )
        }

    /** Carga explícita (p. ej. para medir o precalentar). Devuelve las métricas de carga. */
    suspend fun warmUp(): LoadMetrics? = withContext(dispatcher) { ensureLoaded().second }

    private fun ensureLoaded(): Pair<SupertonicRuntime, LoadMetrics?> {
        runtime?.let { return it to null }
        val dir = SupertonicModelDir(modelDir(), fileSystem)
        val t0 = TimeSource.Monotonic.markNow()
        if (!dir.isVerified()) {
            val bad = dir.verify()
            check(bad.isEmpty()) { "Modelo corrupto o incompleto: ${bad.joinToString()}" }
        }
        val verifyMs = t0.elapsedNow().inWholeMilliseconds
        val before = memory.usedBytes()
        val t1 = TimeSource.Monotonic.markNow()
        val rt = SupertonicRuntime.load(dir, intraOpThreads, onnx())
        val metrics = LoadMetrics(
            loadMs = t1.elapsedNow().inWholeMilliseconds,
            verifyMs = verifyMs,
            memoryBeforeMb = before?.div(MB),
            memoryAfterMb = memory.usedBytes()?.div(MB),
            threads = intraOpThreads,
        )
        runtime = rt
        _stats.update { it.copy(loaded = true, lastLoad = metrics, lastError = null) }
        log("Supertonic cargado en ${metrics.loadMs} ms (verificación ${metrics.verifyMs} ms, memoria ${metrics.memoryBeforeMb}→${metrics.memoryAfterMb} MB, hilos $intraOpThreads)")
        return rt to metrics
    }

    override suspend fun synthesizeTo(request: SpeechRequest, target: Path): SynthesisOutcome {
        val lang = LanguageTags.primary(request.languageTag)
        val readiness = availability(request.languageTag, request.voiceId)
        if (readiness is EngineReadiness.Unavailable) {
            return SynthesisOutcome.Failed(SpeechResult.Failure(readiness.reason, "Supertonic no disponible: ${readiness.reason}"))
        }
        return try {
            withContext(dispatcher) {
                idleJob?.cancel() // idleJob solo se toca en este hilo
                val (rt, load) = ensureLoaded()
                val voice = request.voiceId ?: SupertonicModel.DEFAULT_VOICE
                val totalSteps = request.parameters["steps"]?.toIntOrNull() ?: steps()
                val speed = BASE_SPEED * request.speed
                val chunks = SupertonicTextProcessor.chunk(request.text, SupertonicTextProcessor.maxChunkLength(lang))
                val ctx = coroutineContext
                val t0 = TimeSource.Monotonic.markNow()
                val parts = chunks.mapIndexed { i, chunk ->
                    val audio = rt.infer(chunk, lang, voice, totalSteps, speed, seed = (request.hashCode() * 31L + i)) { ctx.ensureActive() }
                    SupertonicAudio(AudioTrim.trim(audio.samples, audio.sampleRate), audio.sampleRate)
                }
                val synthMs = t0.elapsedNow().inWholeMilliseconds
                val silence = (rt.sampleRate * CHUNK_SILENCE_SEC).toInt()
                val total = parts.sumOf { it.samples.size } + silence * (parts.size - 1)
                val samples = FloatArray(total)
                var pos = 0
                parts.forEachIndexed { i, p ->
                    if (i > 0) pos += silence
                    p.samples.copyInto(samples, pos)
                    pos += p.samples.size
                }
                WavWriter.write(target, samples, rt.sampleRate, fileSystem)
                val metrics = SynthesisMetrics(
                    textChars = request.text.length, language = lang, voice = voice, steps = totalSteps,
                    speed = speed, chunks = chunks.size, loadMs = load?.loadMs, synthMs = synthMs,
                    audioMs = total * 1000L / rt.sampleRate, memoryMb = memory.usedBytes()?.div(MB),
                )
                _stats.update { it.copy(lastSynthesis = metrics, history = (listOf(metrics) + it.history).take(30)) }
                log("Supertonic: ${metrics.textChars} car. → ${metrics.audioMs} ms de audio en ${metrics.synthMs} ms (RTF ${formatFixed(metrics.rtf, 2)}, pasos $totalSteps, mem ${metrics.memoryMb} MB)")
                scheduleIdleRelease()
                SynthesisOutcome.Written(metrics.audioMs)
            }
        } catch (e: OutOfMemoryError) {
            release()
            fail(EngineUnavailableReason.OUT_OF_MEMORY, "Sin memoria al ejecutar Supertonic")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(EngineUnavailableReason.SYNTHESIS_ERROR, e.message ?: (e::class.simpleName ?: "error"))
        }
    }

    private fun fail(reason: EngineUnavailableReason, message: String): SynthesisOutcome {
        _stats.update { it.copy(lastError = message) }
        log("Supertonic error: $message")
        return SynthesisOutcome.Failed(SpeechResult.Failure(reason, message))
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleReleaseMs)
            closeRuntime("inactividad")
        }
    }

    /** Libera las sesiones ONNX (cientos de MB). Seguro de llamar en cualquier momento. */
    override fun release() {
        scope.launch {
            idleJob?.cancel()
            closeRuntime("liberación solicitada")
        }
    }

    private fun closeRuntime(reason: String) {
        val rt = runtime ?: return
        runtime = null
        rt.close()
        _stats.update { it.copy(loaded = false) }
        log("Supertonic liberado ($reason)")
    }

    /** Cierra definitivamente el motor (fin del proceso o tests). */
    fun shutdown() {
        runCatching { kotlinx.coroutines.runBlocking(dispatcher) { closeRuntime("cierre") } }
        dispatcher.close()
    }

    companion object {
        const val DEFAULT_STEPS = 5
        /** Velocidad por defecto de la referencia (1.05) = velocidad 1.0× en la app. */
        const val BASE_SPEED = 1.05f
        const val CHUNK_SILENCE_SEC = 0.3f
        private const val MB = 1024L * 1024L

        fun defaultThreads(): Int = availableProcessors().coerceIn(1, 4)
    }
}
