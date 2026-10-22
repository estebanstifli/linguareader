package com.linguareader.engine.supertonic

import com.linguareader.engine.onnx.OnnxOptLevel
import com.linguareader.engine.onnx.OnnxRuntime
import com.linguareader.engine.onnx.OnnxSession
import com.linguareader.engine.onnx.OnnxSessionOptions
import com.linguareader.engine.onnx.OnnxTensor
import com.linguareader.engine.onnx.closeAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** Resultado de una inferencia: muestras float [-1, 1] a [sampleRate] Hz. */
class SupertonicAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

/**
 * Las cuatro sesiones ONNX de Supertonic (duración → codificador de texto → estimador de vectores
 * con N pasos de flow matching → vocoder). No es seguro para uso concurrente: [SupertonicEngine]
 * lo usa desde un único hilo. Hay que llamar a [close] para liberar la memoria nativa.
 *
 * Nota: el modelo predice solo la **duración total** de cada enunciado; no expone tiempos por
 * carácter ni por palabra.
 */
class SupertonicRuntime private constructor(
    private val runtime: OnnxRuntime,
    private val dp: OnnxSession,
    private val textEnc: OnnxSession,
    private val vectorEst: OnnxSession,
    private val vocoder: OnnxSession,
    private val textProcessor: SupertonicTextProcessor,
    val sampleRate: Int,
    private val baseChunkSize: Int,
    private val chunkCompress: Int,
    private val latentDim: Int,
    private val modelDir: SupertonicModelDir,
) : AutoCloseable {

    private class Style(val ttl: OnnxTensor, val dp: OnnxTensor) : AutoCloseable {
        override fun close() { ttl.close(); dp.close() }
    }

    private val styles = HashMap<String, Style>()

    private fun style(voice: String): Style = styles.getOrPut(voice) {
        require(voice in SupertonicModel.VOICES) { "Voz desconocida: $voice" }
        val root = Json.parseToJsonElement(modelDir.readText("voice_styles/$voice.json")).jsonObject
        Style(tensor3d(root["style_ttl"]!!.jsonObject), tensor3d(root["style_dp"]!!.jsonObject))
    }

    private fun tensor3d(node: JsonObject): OnnxTensor {
        val dims = node["dims"]!!.jsonArray.map { it.jsonPrimitive.long }.toLongArray()
        val flat = FloatArray(dims.fold(1L) { a, b -> a * b }.toInt())
        var idx = 0
        for (a in node["data"]!!.jsonArray) for (b in a as JsonArray) for (c in b as JsonArray) flat[idx++] = c.jsonPrimitive.float
        return runtime.floatTensor(flat, dims)
    }

    /**
     * Sintetiza un texto (ya troceado). [checkCancelled] se llama entre pasos para poder abortar.
     * [seed] hace la salida reproducible para una misma clave de caché.
     */
    fun infer(
        text: String,
        lang: String,
        voice: String,
        totalSteps: Int,
        speed: Float,
        seed: Long,
        checkCancelled: () -> Unit = {},
    ): SupertonicAudio {
        val encoded = textProcessor.encode(text, lang)
        val len = encoded.ids.size
        require(len > 0) { "Texto vacío tras el preprocesado" }
        val st = style(voice)
        val closeables = ArrayList<AutoCloseable>()
        try {
            val textIds = runtime.longTensor(encoded.ids, longArrayOf(1, len.toLong())).also(closeables::add)
            val textMask = runtime.floatTensor(FloatArray(len) { 1f }, longArrayOf(1, 1, len.toLong())).also(closeables::add)

            val dpOut = dp.run(mapOf("text_ids" to textIds, "style_dp" to st.dp, "text_mask" to textMask)).also(closeables::addAll)
            val rawDuration = dpOut[0].floats()[0]
            val durationSec = rawDuration / speed

            val encOut = textEnc.run(mapOf("text_ids" to textIds, "style_ttl" to st.ttl, "text_mask" to textMask)).also(closeables::addAll)
            val textEmb = encOut[0]

            val wavLen = (durationSec * sampleRate).toLong()
            val chunkSize = baseChunkSize * chunkCompress
            val latentLen = max(1, ceil(wavLen.toDouble() / chunkSize).toInt())
            val dim = latentDim * chunkCompress
            val shape = longArrayOf(1, dim.toLong(), latentLen.toLong())

            // Ruido gaussiano (Box-Muller); la máscara es 1 en toda la longitud (lote de 1).
            val rng = Random(seed)
            var xt = FloatArray(dim * latentLen) {
                val u1 = max(1e-10, rng.nextDouble())
                val u2 = rng.nextDouble()
                (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
            }
            val latentMask = runtime.floatTensor(FloatArray(latentLen) { 1f }, longArrayOf(1, 1, latentLen.toLong())).also(closeables::add)
            val totalStep = runtime.floatTensor(floatArrayOf(totalSteps.toFloat()), longArrayOf(1)).also(closeables::add)

            for (step in 0 until totalSteps) {
                checkCancelled()
                val current = runtime.floatTensor(floatArrayOf(step.toFloat()), longArrayOf(1))
                val noisy = runtime.floatTensor(xt, shape)
                try {
                    val out = vectorEst.run(
                        mapOf(
                            "noisy_latent" to noisy, "text_emb" to textEmb, "style_ttl" to st.ttl,
                            "latent_mask" to latentMask, "text_mask" to textMask,
                            "current_step" to current, "total_step" to totalStep,
                        ),
                    )
                    try {
                        xt = out[0].floats()
                    } finally {
                        closeAll(out)
                    }
                } finally {
                    noisy.close()
                    current.close()
                }
            }
            checkCancelled()

            val latent = runtime.floatTensor(xt, shape)
            val samples = try {
                val out = vocoder.run(mapOf("latent" to latent))
                try {
                    val all = out[0].floats()
                    val n = minOf(all.size.toLong(), wavLen).toInt()
                    if (n == all.size) all else all.copyOf(n)
                } finally {
                    closeAll(out)
                }
            } finally {
                latent.close()
            }
            return SupertonicAudio(samples, sampleRate)
        } finally {
            closeAll(closeables)
        }
    }

    override fun close() {
        styles.values.forEach { runCatching { it.close() } }
        styles.clear()
        listOf(dp, textEnc, vectorEst, vocoder).forEach { runCatching { it.close() } }
    }

    companion object {
        /** Carga las cuatro sesiones. Operación lenta (segundos) y de cientos de MB: fuera del hilo principal. */
        fun load(dir: SupertonicModelDir, intraOpThreads: Int, runtime: OnnxRuntime): SupertonicRuntime {
            val cfg = Json.parseToJsonElement(dir.readText("onnx/tts.json")).jsonObject
            val ae = cfg["ae"]!!.jsonObject
            val ttl = cfg["ttl"]!!.jsonObject
            val indexer = Json.parseToJsonElement(dir.readText("onnx/unicode_indexer.json")).jsonArray
                .map { it.jsonPrimitive.long }.toLongArray()

            val opened = ArrayList<OnnxSession>()
            try {
                fun session(name: String): OnnxSession = runtime.createSession(
                    dir.file("onnx/$name.onnx").toString(),
                    OnnxSessionOptions(intraOpThreads = intraOpThreads, interOpThreads = 1, optimization = OnnxOptLevel.EXTENDED),
                ).also(opened::add)
                return SupertonicRuntime(
                    runtime = runtime,
                    dp = session("duration_predictor"),
                    textEnc = session("text_encoder"),
                    vectorEst = session("vector_estimator"),
                    vocoder = session("vocoder"),
                    textProcessor = SupertonicTextProcessor(indexer),
                    sampleRate = ae["sample_rate"]!!.jsonPrimitive.int,
                    baseChunkSize = ae["base_chunk_size"]!!.jsonPrimitive.int,
                    chunkCompress = ttl["chunk_compress_factor"]!!.jsonPrimitive.int,
                    latentDim = ttl["latent_dim"]!!.jsonPrimitive.int,
                    modelDir = dir,
                )
            } catch (t: Throwable) {
                opened.forEach { runCatching { it.close() } }
                throw t
            }
        }
    }
}
