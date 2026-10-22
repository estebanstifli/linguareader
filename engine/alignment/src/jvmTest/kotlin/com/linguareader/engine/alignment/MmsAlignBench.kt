package com.linguareader.engine.alignment

import okio.Path.Companion.toOkioPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.abs

/**
 * Banco de pruebas MMS-ONNX (int8 / q4) sobre el corpus de Supertonic, comparado con la
 * verdad de referencia y con MMS f16 de audio.cpp (salida de tools/eval_aligners.py).
 */
class MmsAlignBench {

    private val models = File(System.getProperty("align.models", ".models"))
    private val corpusDir = File(models, "align-corpus")

    private fun committedMb(): Long =
        ((ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.committedVirtualMemorySize ?: 0) / 1_000_000

    @Test
    fun bench() {
        assumeTrue(System.getProperty("align.bench") == "true")
        assumeTrue(File(corpusDir, "corpus.json").exists())
        val corpus = Json.parseToJsonElement(File(corpusDir, "corpus.json").readText()).jsonArray.map { it.jsonObject }
        val vocab = File(models, "aligners/mms-onnx/vocab.json")
        val report = StringBuilder()
        for (variant in listOf("model_uint8.onnx", "model_q4.onnx", "model_int8.onnx")) {
            val model = File(models, "aligners/mms-onnx/onnx/$variant")
            if (!model.exists()) continue
            for (threads in listOf(4, 12)) {
                val mem0 = committedMb()
                val t0 = System.nanoTime()
                val aligner = try {
                    MmsOnnxAligner.load(model.toOkioPath(), vocab.toOkioPath(), threads)
                } catch (e: Exception) {
                    println("$variant: no carga en ONNX Runtime CPU → ${e.message?.take(160)}")
                    report.appendLine("$variant: no carga (${e.message?.take(120)})")
                    break
                }
                val loadMs = (System.nanoTime() - t0) / 1e6
                val memLoaded = committedMb()
                val startErr = ArrayList<Double>()
                val endErr = ArrayList<Double>()
                val vsAudioCpp = ArrayList<Double>()
                var infer = 0.0
                var audio = 0.0
                for (item in corpus) {
                    val words = item["words"]!!.jsonArray.map { it.jsonPrimitive.content }
                    val (samples, sr) = MmsOnnxAligner.readWav(File(corpusDir, item["wav"]!!.jsonPrimitive.content).toOkioPath())
                    val s = System.nanoTime()
                    val timings = aligner.align(samples, sr, words)
                    infer += (System.nanoTime() - s) / 1e9
                    audio += samples.size.toDouble() / sr
                    item["truth"]?.jsonArray?.let { truth ->
                        timings.forEach { w ->
                            val t = truth[w.wordIndex].jsonObject
                            startErr += abs(w.startSec - (t["start"]!!.jsonPrimitive.double + 0.04))
                            endErr += abs(w.endSec - (t["end"]!!.jsonPrimitive.double - 0.04))
                        }
                    }
                    val ac = File(corpusDir, "out/mms/${item["id"]!!.jsonPrimitive.content}.json")
                    if (ac.exists()) {
                        val acWords = Json.parseToJsonElement(ac.readText()) as JsonArray
                        if (acWords.size == timings.size) timings.zip(acWords).forEach { (w, a) ->
                            vsAudioCpp += abs(w.startSec - a.jsonObject["start_sample"]!!.jsonPrimitive.long / 16_000.0)
                        }
                    }
                }
                aligner.close()
                fun ms(l: List<Double>) = if (l.isEmpty()) "-" else "%.0f".format(1000 * l.average())
                fun p90(l: List<Double>) = if (l.isEmpty()) "-" else "%.0f".format(1000 * l.sorted()[(0.9 * (l.size - 1)).toInt()])
                val line = "%-17s hilos=%2d carga=%5.0f ms  RTF=%.3f  mem +%4d MB  ref: inicio %s ms (p90 %s), fin %s ms, ≤50ms %.0f%%  vs audio.cpp f16: %s ms".format(
                    variant, threads, loadMs, infer / audio, memLoaded - mem0, ms(startErr), p90(startErr), ms(endErr),
                    100.0 * startErr.count { it <= 0.05 } / startErr.size.coerceAtLeast(1), ms(vsAudioCpp),
                )
                println(line)
                report.appendLine(line)
            }
        }
        File(corpusDir, "mms_onnx_bench.txt").writeText(report.toString())
    }
}
