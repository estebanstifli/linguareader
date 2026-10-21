package com.linguareader.engine.alignment

import com.linguareader.core.alignment.ClipAligner
import com.linguareader.core.alignment.WordSpanMs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Ficheros de un modelo de alineación instalado. [version] identifica el modelo (id, revisión y tamaño) para
 * invalidar alineaciones guardadas si cambia. [startLeadMs]: adelanto de los inicios de palabra que compensa el
 * retraso con que el CTC emite la primera letra (medido por modelo con `alignBench`; ver docs/ALIGNMENT.md).
 */
class AlignerModelFiles(
    val model: File,
    val vocab: File,
    val version: String = "${model.name}:${model.length()}",
    val startLeadMs: Long = 0,
)

/**
 * Adelanta [leadMs] el inicio de cada palabra sin cruzar el fin de la anterior (ni bajar de 0) ni pasar de su
 * propio fin: los tiempos siguen siendo monótonos y sin solapes.
 */
fun advanceStarts(spans: List<WordSpanMs>, leadMs: Long): List<WordSpanMs> {
    if (leadMs <= 0) return spans
    var prevEnd = 0L
    return spans.map { s ->
        val start = (s.startMs - leadMs).coerceAtLeast(prevEnd).coerceAtMost(s.startMs)
        prevEnd = maxOf(prevEnd, s.endMs)
        s.copy(startMs = start)
    }
}

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

    override fun signature(languageTag: String): String? = modelFor(code(languageTag))?.let { "w2v2-ctc:${it.version}:lead${it.startLeadMs}" }

    override suspend fun align(clipPath: String, words: List<String>, languageTag: String): List<WordSpanMs>? {
        val lang = code(languageTag)
        val (samples, sr) = MmsOnnxAligner.readWav(File(clipPath))
        // Un clip a la vez: la sesión se comparte y su memoria es el recurso escaso.
        return synchronized(lock) {
            val files = modelFor(lang) ?: return null
            val aligner = aligner(lang, files)
            val t0 = System.nanoTime()
            val timings = try {
                aligner.align(samples, sr, words)
            } catch (e: Exception) {
                log("la alineación falló: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            log("alineadas ${timings.size}/${words.size} palabras de ${"%.1f".format(samples.size.toDouble() / sr)} s en ${(System.nanoTime() - t0) / 1_000_000} ms")
            scheduleIdleRelease()
            advanceStarts(
                timings.map { WordSpanMs(it.wordIndex, (it.startSec * 1000).roundToLong(), (it.endSec * 1000).roundToLong(), exp(it.meanLogProb)) },
                files.startLeadMs,
            )
        }
    }

    override fun release() = synchronized(lock) {
        idleTask?.cancel(false)
        idleTask = null
        loaded?.close()
        loaded = null
        loadedLanguage = null
    }

    private fun aligner(lang: String, files: AlignerModelFiles): Wav2Vec2OnnxAligner {
        if (loadedLanguage == lang) loaded?.let { return it }
        loaded?.close()
        loaded = null
        loadedLanguage = null
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
