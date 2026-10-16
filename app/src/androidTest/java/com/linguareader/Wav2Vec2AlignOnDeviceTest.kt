package com.linguareader

import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.engine.alignment.MmsOnnxAligner
import com.linguareader.engine.alignment.Wav2Vec2OnnxAligner
import com.linguareader.engine.alignment.WordTiming
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
 * Experimento wav2vec2 vs MMS en el dispositivo, sobre las frases con verdad de referencia del idioma.
 * Argumentos: aligner=mms|w2v2, lang=en|fr, model=<fichero dentro de files/aligners/<dir>/>, dir=<carpeta>.
 * Se omite si no se han copiado modelo y corpus (ver docs/ALIGNMENT.md).
 */
class Wav2Vec2AlignOnDeviceTest {

    @Test
    fun alignReferenceOnDevice() {
        val args = InstrumentationRegistry.getArguments()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(ctx.filesDir, "aligners")
        val kind = args.getString("aligner") ?: "w2v2"
        val lang = args.getString("lang") ?: "en"
        val dir = File(root, args.getString("dir") ?: if (kind == "mms") "mms-onnx" else "w2v2-en")
        val model = File(dir, args.getString("model") ?: "model_int8.onnx")
        assumeTrue("Copia el modelo a ${model.path}", model.exists() && File(root, "corpus.json").exists())

        val pssBefore = Debug.getPss() / 1024
        val t0 = System.nanoTime()
        val align: (FloatArray, Int, List<String>) -> List<WordTiming>
        val close: () -> Unit
        if (kind == "mms") {
            val a = MmsOnnxAligner.load(model, File(dir, "vocab.json"), threads = 4); align = a::align; close = a::close
        } else {
            val a = Wav2Vec2OnnxAligner.load(model, File(dir, "vocab.json"), threads = 4); align = a::align; close = a::close
        }
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        val pssLoaded = Debug.getPss() / 1024

        val corpus = Json.parseToJsonElement(File(root, "corpus.json").readText()).jsonArray.map { it.jsonObject }
            .filter { it["id"]!!.jsonPrimitive.content.startsWith("ref_${lang}_") }
        var infer = 0.0
        var audio = 0.0
        var pssPeak = pssLoaded
        val start = ArrayList<Double>()
        val end = ArrayList<Double>()
        for (item in corpus) {
            val wav = File(root, item["wav"]!!.jsonPrimitive.content.substringAfterLast('/'))
            if (!wav.exists()) continue
            val words = item["words"]!!.jsonArray.map { it.jsonPrimitive.content }
            val (samples, sr) = MmsOnnxAligner.readWav(wav)
            val s = System.nanoTime()
            val timings = align(samples, sr, words)
            infer += (System.nanoTime() - s) / 1e9
            audio += samples.size.toDouble() / sr
            pssPeak = maxOf(pssPeak, Debug.getPss() / 1024)
            val truth = item["truth"]!!.jsonArray
            for (w in timings) {
                val t = truth[w.wordIndex].jsonObject
                start += abs(w.startSec - (t["start"]!!.jsonPrimitive.double + 0.04))
                end += abs(w.endSec - (t["end"]!!.jsonPrimitive.double - 0.04))
            }
        }
        close()
        fun ms(l: List<Double>) = "%.0f".format(1000 * l.average())
        fun p90(l: List<Double>) = "%.0f".format(1000 * l.sorted()[(0.9 * (l.size - 1)).toInt()])
        Log.i(
            "W2vAlignBench",
            "$kind/$lang ${model.name} (${model.length() / 1_000_000} MB) frases=${corpus.size} palabras=${start.size} carga=${loadMs}ms " +
                "audio=${"%.1f".format(audio)}s RTF=${"%.3f".format(infer / audio)} PSS ${pssBefore}→${pssLoaded}→${pssPeak} MB " +
                "inicio=${ms(start)}ms (p90 ${p90(start)}) fin=${ms(end)}ms (p90 ${p90(end)}) ≤50ms=${"%.0f".format(100.0 * start.count { it <= 0.05 } / start.size)}%",
        )
        assumeTrue(audio > 0)
    }
}
