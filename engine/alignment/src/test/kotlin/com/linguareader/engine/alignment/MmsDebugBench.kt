package com.linguareader.engine.alignment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Diagnóstico: compara palabra a palabra MMS-ONNX, audio.cpp MMS y la referencia en una frase. */
class MmsDebugBench {
    @Test
    fun compareOne() {
        assumeTrue(System.getProperty("align.bench") == "true")
        val models = File(System.getProperty("align.models"))
        val corpus = File(models, "align-corpus")
        val item = Json.parseToJsonElement(File(corpus, "corpus.json").readText()).jsonArray.map { it.jsonObject }
            .first { it["id"]!!.jsonPrimitive.content == "ref_en_0" }
        val words = item["words"]!!.jsonArray.map { it.jsonPrimitive.content }
        val (samples, sr) = MmsOnnxAligner.readWav(File(corpus, item["wav"]!!.jsonPrimitive.content))
        val aligner = MmsOnnxAligner.load(File(models, "aligners/mms-onnx/onnx/model_q4.onnx"), File(models, "aligners/mms-onnx/vocab.json"), 12)
        val mine = aligner.align(samples, sr, words)
        val ac = Json.parseToJsonElement(File(corpus, "out/mms/ref_en_0.json").readText()) as JsonArray
        val truth = item["truth"]!!.jsonArray
        println("palabra      ref(ini-fin)       audio.cpp          onnx")
        mine.forEachIndexed { i, w ->
            val t = truth[w.wordIndex].jsonObject
            val a = ac[i].jsonObject
            println("%-10s %5.2f-%5.2f   %5.2f-%5.2f   %5.2f-%5.2f".format(
                words[w.wordIndex], t["start"]!!.jsonPrimitive.content.toDouble() + 0.04, t["end"]!!.jsonPrimitive.content.toDouble() - 0.04,
                a["start_sample"]!!.jsonPrimitive.content.toDouble() / 16000, a["end_sample"]!!.jsonPrimitive.content.toDouble() / 16000,
                w.startSec, w.endSec))
        }
        // También sin remuestrear nosotros: usar el WAV a 16 kHz de ffmpeg que usó audio.cpp.
        val f16k = File(corpus, "16k/reference/ref_en_0.wav")
        if (f16k.exists()) {
            val (s16, sr16) = MmsOnnxAligner.readWav16k(f16k)
            val viaFfmpeg = aligner.align(s16, sr16, words)
            println("con audio de ffmpeg 16k: " + viaFfmpeg.joinToString { "%.2f".format(it.startSec) })
        }
    }
}
