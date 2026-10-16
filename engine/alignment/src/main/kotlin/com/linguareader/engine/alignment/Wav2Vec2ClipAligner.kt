package com.linguareader.engine.alignment

import com.linguareader.core.alignment.ClipAligner
import com.linguareader.core.alignment.WordSpanMs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.roundToLong

/** Ficheros de un modelo de alineación instalado. */
class AlignerModelFiles(val model: File, val vocab: File)

/**
 * [ClipAligner] con modelos wav2vec2 CTC por idioma ([Wav2Vec2OnnxAligner]). Carga el modelo del
 * idioma bajo demanda y lo libera tras [idleReleaseMs] sin uso o al cambiar de idioma, para no
 * competir por RAM con el sintetizador.
 *
 * @param modelFor modelo instalado para un código de idioma (`en`, `fr`…) o null si no hay.
 */
class Wav2Vec2ClipAligner(
    private val modelFor: (language: String) -> AlignerModelFiles?,
    private val threads: Int = 4,
    private val idleReleaseMs: Long = 30_000,
    private val log: (String) -> Unit = {},
) : ClipAligner {

    override val id = "wav2vec2-ctc"

    private val lock = Any()
    private var loadedLanguage: String? = null
    private var loaded: Wav2Vec2OnnxAligner? = null
    private var idleTask: ScheduledFuture<*>? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aligner-idle").apply { isDaemon = true } }

    override fun isAvailable(languageTag: String): Boolean = modelFor(code(languageTag)) != null

    override suspend fun align(clipPath: String, words: List<String>, languageTag: String): List<WordSpanMs>? {
        val lang = code(languageTag)
        val (samples, sr) = MmsOnnxAligner.readWav(File(clipPath))
        // Un clip a la vez: la sesión se comparte y su memoria es el recurso escaso.
        return synchronized(lock) {
            val aligner = aligner(lang) ?: return null
            val t0 = System.nanoTime()
            val timings = aligner.align(samples, sr, words)
            log("alineadas ${timings.size}/${words.size} palabras de ${"%.1f".format(samples.size.toDouble() / sr)} s en ${(System.nanoTime() - t0) / 1_000_000} ms")
            scheduleIdleRelease()
            timings.map { WordSpanMs(it.wordIndex, (it.startSec * 1000).roundToLong(), (it.endSec * 1000).roundToLong(), exp(it.meanLogProb)) }
        }
    }

    override fun release() = synchronized(lock) {
        idleTask?.cancel(false)
        idleTask = null
        loaded?.close()
        loaded = null
        loadedLanguage = null
    }

    private fun aligner(lang: String): Wav2Vec2OnnxAligner? {
        if (loadedLanguage == lang) loaded?.let { return it }
        loaded?.close()
        loaded = null
        loadedLanguage = null
        val files = modelFor(lang) ?: return null
        val t0 = System.nanoTime()
        return Wav2Vec2OnnxAligner.load(files.model, files.vocab, threads).also {
            loaded = it
            loadedLanguage = lang
            log("modelo de alineación '$lang' cargado en ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    private fun scheduleIdleRelease() {
        idleTask?.cancel(false)
        idleTask = timer.schedule({ release() }, idleReleaseMs, TimeUnit.MILLISECONDS)
    }

    private fun code(languageTag: String) = languageTag.substringBefore('-').substringBefore('_').lowercase()
}
