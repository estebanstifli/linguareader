package com.linguareader.engine.alignment

import okio.Path.Companion.toOkioPath
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
 * Modelos wav2vec2 por idioma frente a MMS-300M (referencia) sobre las frases con verdad de
 * referencia del corpus de Supertonic: EN wav2vec2-base-100h, FR large 7K y VoxPopuli base, ES
 * VoxPopuli base. `./gradlew :engine:alignment:alignBench` (`-Dalign.int8Only=true`: solo int8).
 */
class Wav2Vec2AlignBench {

    private val models = File(System.getProperty("align.models", ".models"))
    private val corpusDir = File(models, "align-corpus")

    private fun committedMb(): Long =
        ((ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.committedVirtualMemorySize ?: 0) / 1_000_000

    private class Row(val name: String, val sizeMb: Long, val threads: Int, val loadMs: Double, val rtf: Double, val memMb: Long,
                      val start: List<Double>, val end: List<Double>, val bias: List<Double>)

    @Test
    fun bench() {
        assumeTrue(System.getProperty("align.bench") == "true")
        assumeTrue(File(corpusDir, "corpus.json").exists())
        val corpus = Json.parseToJsonElement(File(corpusDir, "corpus.json").readText()).jsonArray.map { it.jsonObject }
        val runs = listOf("en" to "w2v2-base-100h", "fr" to "w2v2-fr", "fr" to "vp-fr", "es" to "vp-es")
        val withMms = HashSet<String>()
        val only = System.getProperty("align.dirs")?.split(',')?.toSet()
        for ((lang, dirName) in runs.filter { only == null || it.second in only }) runLang(corpus, lang, File(models, "aligners/$dirName"), withMms.add(lang))
    }

    private fun runLang(corpus: List<kotlinx.serialization.json.JsonObject>, lang: String, w2vDir: File, withMms: Boolean) {
        if (!w2vDir.exists()) return
        val items = corpus.filter { it["id"]!!.jsonPrimitive.content.startsWith("ref_${lang}_") }
        val rows = ArrayList<Row>()

        fun run(name: String, file: File, threads: Int, load: () -> Pair<(FloatArray, Int, List<String>) -> List<WordTiming>, () -> Unit>) {
            val mem0 = committedMb()
            val t0 = System.nanoTime()
            val (align, close) = load()
            val loadMs = (System.nanoTime() - t0) / 1e6
            val memLoaded = committedMb() - mem0
            val start = ArrayList<Double>(); val end = ArrayList<Double>(); val bias = ArrayList<Double>()
            var infer = 0.0; var audio = 0.0; var peak = memLoaded
            for (item in items) {
                val words = item["words"]!!.jsonArray.map { it.jsonPrimitive.content }
                val (samples, sr) = MmsOnnxAligner.readWav(File(corpusDir, item["wav"]!!.jsonPrimitive.content).toOkioPath())
                val s = System.nanoTime()
                val timings = align(samples, sr, words)
                infer += (System.nanoTime() - s) / 1e9
                audio += samples.size.toDouble() / sr
                peak = maxOf(peak, committedMb() - mem0)
                item["truth"]?.jsonArray?.let { truth ->
                    timings.forEach { w ->
                        val t = truth[w.wordIndex].jsonObject
                        bias += w.startSec - (t["start"]!!.jsonPrimitive.double + 0.04)
                        start += abs(bias.last())
                        end += abs(w.endSec - (t["end"]!!.jsonPrimitive.double - 0.04))
                    }
                }
            }
            close()
            rows += Row(name, file.length() / 1_000_000, threads, loadMs, infer / audio, peak, start, end, bias)
        }

        for (threads in (System.getProperty("align.threads") ?: "4,12").split(',').map { it.trim().toInt() }) {
            val mmsFile = File(models, "aligners/mms-onnx/onnx/model_uint8.onnx")
            if (withMms && mmsFile.exists()) {
                run("MMS uint8 (ref.)", mmsFile, threads) {
                    val a = MmsOnnxAligner.load(mmsFile.toOkioPath(), File(models, "aligners/mms-onnx/vocab.json").toOkioPath(), threads)
                    (a::align) to { a.close() }
                }
            }
            val variants = if (System.getProperty("align.int8Only") == "true") listOf("model_int8.onnx")
                else listOf("model.onnx", "model_fp16.onnx", "model_int8.onnx").filter { System.getProperty("align.skipFp32") != "true" || it != "model.onnx" || lang == "en" }
            for (variant in variants) {
                val f = File(w2vDir, "onnx/$variant")
                if (!f.exists()) continue
                try {
                    run("${w2vDir.name} $variant", f, threads) {
                        val a = Wav2Vec2OnnxAligner.load(f.toOkioPath(), File(w2vDir, "vocab.json").toOkioPath(), threads)
                        (a::align) to { a.close() }
                    }
                } catch (e: Exception) {
                    println("$variant: no funciona en ORT CPU → ${e.message?.take(160)}")
                }
            }
        }
        fun ms(l: List<Double>) = if (l.isEmpty()) "-" else "%.0f".format(1000 * l.average())
        fun p90(l: List<Double>) = if (l.isEmpty()) "-" else "%.0f".format(1000 * l.sorted()[(0.9 * (l.size - 1)).toInt()])
        val sb = StringBuilder("idioma=$lang, modelo=${w2vDir.name}, ${items.size} frases\n")
        for (r in rows) sb.appendLine("%-28s %4d MB hilos=%2d carga=%5.0f ms RTF=%.3f mem +%4d MB | ini %s ms (p90 %s) fin %s ms (p90 %s) ≤50ms %.0f%% sesgo ini %s ms n=%d".format(
            r.name, r.sizeMb, r.threads, r.loadMs, r.rtf, r.memMb, ms(r.start), p90(r.start), ms(r.end), p90(r.end),
            100.0 * r.start.count { it <= 0.05 } / r.start.size.coerceAtLeast(1), ms(r.bias), r.start.size))
        // Error de inicio si se adelantaran todos los inicios L ms (corrección del retraso de emisión CTC).
        for (r in rows.filter { it.threads == rows.first().threads }) sb.appendLine("  %-26s adelanto→ini medio: %s".format(r.name,
            (0..120 step 20).joinToString("  ") { l -> "$l:%.0f(%.0f%%)".format(1000 * r.bias.map { abs(it - l / 1000.0) }.average(), 100.0 * r.bias.count { abs(it - l / 1000.0) <= 0.05 } / r.bias.size.coerceAtLeast(1)) }))
        println(sb)
        File(corpusDir, "w2v2_bench_${lang}_${w2vDir.name}.txt").writeText(sb.toString())
    }
}
