package com.linguareader.engine.alignment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.abs

/**
 * Experimento 1: wav2vec2-base-100h (Apache-2.0, inglés) frente a MMS-300M (referencia) sobre las
 * frases en inglés del corpus de Supertonic. `./gradlew :engine:alignment:alignBench`.
 */
class Wav2Vec2AlignBench {

    private val models = File(System.getProperty("align.models", ".models"))
    private val corpusDir = File(models, "align-corpus")

    private fun committedMb(): Long =
        ((ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.committedVirtualMemorySize ?: 0) / 1_000_000

    private class Row(val name: String, val sizeMb: Long, val threads: Int, val loadMs: Double, val rtf: Double, val memMb: Long,
                      val start: List<Double>, val end: List<Double>)

    @Test
    fun bench() {
        assumeTrue(System.getProperty("align.bench") == "true")
        assumeTrue(File(corpusDir, "corpus.json").exists())
        val corpus = Json.parseToJsonElement(File(corpusDir, "corpus.json").readText()).jsonArray.map { it.jsonObject }
        for ((lang, dirName) in listOf("en" to "w2v2-base-100h", "fr" to "w2v2-fr")) runLang(corpus, lang, File(models, "aligners/$dirName"))
    }

    private fun runLang(corpus: List<kotlinx.serialization.json.JsonObject>, lang: String, w2vDir: File) {
        if (!w2vDir.exists()) return
        val items = corpus.filter { it["id"]!!.jsonPrimitive.content.startsWith("ref_${lang}_") }
        val rows = ArrayList<Row>()

        fun run(name: String, file: File, threads: Int, load: () -> Pair<(FloatArray, Int, List<String>) -> List<WordTiming>, () -> Unit>) {
            val mem0 = committedMb()
            val t0 = System.nanoTime()
            val (align, close) = load()
            val loadMs = (System.nanoTime() - t0) / 1e6
            val memLoaded = committedMb() - mem0
            val start = ArrayList<Double>(); val end = ArrayList<Double>()
            var infer = 0.0; var audio = 0.0; var peak = memLoaded
            for (item in items) {
                val words = item["words"]!!.jsonArray.map { it.jsonPrimitive.content }
                val (samples, sr) = MmsOnnxAligner.readWav(File(corpusDir, item["wav"]!!.jsonPrimitive.content))
                val s = System.nanoTime()
                val timings = align(samples, sr, words)
                infer += (System.nanoTime() - s) / 1e9
                audio += samples.size.toDouble() / sr
                peak = maxOf(peak, committedMb() - mem0)
                item["truth"]?.jsonArray?.let { truth ->
                    timings.forEach { w ->
                        val t = truth[w.wordIndex].jsonObject
                        start += abs(w.startSec - (t["start"]!!.jsonPrimitive.double + 0.04))
                        end += abs(w.endSec - (t["end"]!!.jsonPrimitive.double - 0.04))
                    }
                }
            }
            close()
            rows += Row(name, file.length() / 1_000_000, threads, loadMs, infer / audio, peak, start, end)
        }

        for (threads in listOf(4, 12)) {
            val mmsFile = File(models, "aligners/mms-onnx/onnx/model_uint8.onnx")
            if (mmsFile.exists()) {
                run("MMS uint8 (ref.)", mmsFile, threads) {
                    val a = MmsOnnxAligner.load(mmsFile, File(models, "aligners/mms-onnx/vocab.json"), threads)
                    (a::align) to { a.close() }
                }
            }
            for (variant in listOf("model.onnx", "model_fp16.onnx", "model_int8.onnx").filter { System.getProperty("align.skipFp32") != "true" || it != "model.onnx" || lang == "en" }) {
                val f = File(w2vDir, "onnx/$variant")
                if (!f.exists()) continue
                try {
                    run("w2v2-$lang $variant", f, threads) {
                        val a = Wav2Vec2OnnxAligner.load(f, File(w2vDir, "vocab.json"), threads)
                        (a::align) to { a.close() }
                    }
                } catch (e: Exception) {
                    println("$variant: no funciona en ORT CPU → ${e.message?.take(160)}")
                }
            }
        }
        fun ms(l: List<Double>) = if (l.isEmpty()) "-" else "%.0f".format(1000 * l.average())
        fun p90(l: List<Double>) = if (l.isEmpty()) "-" else "%.0f".format(1000 * l.sorted()[(0.9 * (l.size - 1)).toInt()])
        val sb = StringBuilder("idioma=$lang, ${items.size} frases\n")
        for (r in rows) sb.appendLine("%-28s %4d MB hilos=%2d carga=%5.0f ms RTF=%.3f mem +%4d MB | ini %s ms (p90 %s) fin %s ms (p90 %s) ≤50ms %.0f%% n=%d".format(
            r.name, r.sizeMb, r.threads, r.loadMs, r.rtf, r.memMb, ms(r.start), p90(r.start), ms(r.end), p90(r.end),
            100.0 * r.start.count { it <= 0.05 } / r.start.size.coerceAtLeast(1), r.start.size))
        println(sb)
        File(corpusDir, "w2v2_bench_$lang.txt").writeText(sb.toString())
    }
}
