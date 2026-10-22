package com.linguareader

import okio.Path.Companion.toOkioPath
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.engine.alignment.MmsOnnxAligner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Banco de MMS (ONNX q4) en el dispositivo. Se omite si no se han copiado el modelo y el corpus a
 * files/aligners/ (tools: `run-as` desde .models/, ver docs/ALIGNMENT.md).
 */
class MmsAlignOnDeviceTest {

    @Test
    fun alignCorpusOnDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "aligners")
        val name = InstrumentationRegistry.getArguments().getString("model") ?: "model_q4.onnx"
        val model = File(dir, name)
        assumeTrue("Copia el modelo a ${model.path}", model.exists())
        val pssBefore = Debug.getPss() / 1024
        val t0 = System.nanoTime()
        val aligner = MmsOnnxAligner.load(model.toOkioPath(), File(dir, "vocab.json").toOkioPath(), threads = 4)
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        val pssLoaded = Debug.getPss() / 1024
        val corpus = Json.parseToJsonElement(File(dir, "corpus.json").readText()).jsonArray.map { it.jsonObject }
        var infer = 0.0
        var audio = 0.0
        val err = ArrayList<Double>()
        for (item in corpus) {
            val wav = File(dir, item["wav"]!!.jsonPrimitive.content.substringAfterLast('/'))
            if (!wav.exists()) continue
            val words = item["words"]!!.jsonArray.map { it.jsonPrimitive.content }
            val (samples, sr) = MmsOnnxAligner.readWav(wav.toOkioPath())
            val s = System.nanoTime()
            val timings = aligner.align(samples, sr, words)
            infer += (System.nanoTime() - s) / 1e9
            audio += samples.size.toDouble() / sr
            item["truth"]?.jsonArray?.let { truth ->
                timings.forEach { w -> err += abs(w.startSec - (truth[w.wordIndex].jsonObject["start"]!!.jsonPrimitive.double + 0.04)) }
            }
        }
        val pssPeak = Debug.getPss() / 1024
        aligner.close()
        Log.i(
            "MmsAlignBench",
            "$name carga=${loadMs}ms audio=${"%.1f".format(audio)}s inferencia=${"%.1f".format(infer)}s RTF=${"%.3f".format(infer / audio)} " +
                "PSS ${pssBefore}→${pssLoaded}→${pssPeak} MB errorInicio=${"%.0f".format(1000 * err.average())}ms (${err.size} palabras)",
        )
        assumeTrue(audio > 0)
    }
}
