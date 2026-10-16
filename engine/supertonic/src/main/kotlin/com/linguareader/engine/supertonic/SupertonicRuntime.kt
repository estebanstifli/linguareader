package com.linguareader.engine.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.float
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

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
    private val env: OrtEnvironment,
    private val dp: OrtSession,
    private val textEnc: OrtSession,
    private val vectorEst: OrtSession,
    private val vocoder: OrtSession,
    private val textProcessor: SupertonicTextProcessor,
    val sampleRate: Int,
    private val baseChunkSize: Int,
    private val chunkCompress: Int,
    private val latentDim: Int,
    private val modelDir: SupertonicModelDir,
) : Closeable {

    private class Style(val ttl: OnnxTensor, val dp: OnnxTensor) : Closeable {
        override fun close() { ttl.close(); dp.close() }
    }

    private val styles = HashMap<String, Style>()

    private fun style(voice: String): Style = styles.getOrPut(voice) {
        require(voice in SupertonicModel.VOICES) { "Voz desconocida: $voice" }
        val root = Json.parseToJsonElement(modelDir.file("voice_styles/$voice.json").readText()).jsonObject
        Style(tensor3d(root["style_ttl"]!!.jsonObject), tensor3d(root["style_dp"]!!.jsonObject))
    }

    private fun tensor3d(node: JsonObject): OnnxTensor {
        val dims = node["dims"]!!.jsonArray.map { it.jsonPrimitive.long }.toLongArray()
        val flat = FloatArray(dims.fold(1L) { a, b -> a * b }.toInt())
        var idx = 0
        for (a in node["data"]!!.jsonArray) for (b in a as JsonArray) for (c in b as JsonArray) flat[idx++] = c.jsonPrimitive.float
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), dims)
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
            val textIds = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.ids), longArrayOf(1, len.toLong())).also(closeables::add)
            val textMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(len) { 1f }), longArrayOf(1, 1, len.toLong())).also(closeables::add)

            val dpOut = dp.run(mapOf("text_ids" to textIds, "style_dp" to st.dp, "text_mask" to textMask)).also(closeables::add)
            val rawDuration = (dpOut.get(0) as OnnxTensor).floatBuffer.get(0)
            val durationSec = rawDuration / speed

            val encOut = textEnc.run(mapOf("text_ids" to textIds, "style_ttl" to st.ttl, "text_mask" to textMask)).also(closeables::add)
            val textEmb = encOut.get(0) as OnnxTensor

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
                (sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)).toFloat()
            }
            val latentMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(latentLen) { 1f }), longArrayOf(1, 1, latentLen.toLong()))
                .also(closeables::add)
            val totalStep = OnnxTensor.createTensor(env, floatArrayOf(totalSteps.toFloat())).also(closeables::add)

            for (step in 0 until totalSteps) {
                checkCancelled()
                OnnxTensor.createTensor(env, floatArrayOf(step.toFloat())).use { current ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), shape).use { noisy ->
                        vectorEst.run(
                            mapOf(
                                "noisy_latent" to noisy, "text_emb" to textEmb, "style_ttl" to st.ttl,
                                "latent_mask" to latentMask, "text_mask" to textMask,
                                "current_step" to current, "total_step" to totalStep,
                            ),
                        ).use { out ->
                            val fb = (out.get(0) as OnnxTensor).floatBuffer
                            xt = FloatArray(fb.remaining()).also { fb.get(it) }
                        }
                    }
                }
            }
            checkCancelled()

            val samples = OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), shape).use { latent ->
                vocoder.run(mapOf("latent" to latent)).use { out ->
                    val fb = (out.get(0) as OnnxTensor).floatBuffer
                    val n = minOf(fb.remaining().toLong(), wavLen).toInt()
                    FloatArray(n).also { fb.get(it, 0, n) }
                }
            }
            return SupertonicAudio(samples, sampleRate)
        } finally {
            closeables.asReversed().forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        styles.values.forEach { runCatching { it.close() } }
        styles.clear()
        listOf(dp, textEnc, vectorEst, vocoder).forEach { runCatching { it.close() } }
    }

    companion object {
        /** Carga las cuatro sesiones. Operación lenta (segundos) y de cientos de MB: fuera del hilo principal. */
        fun load(dir: SupertonicModelDir, intraOpThreads: Int): SupertonicRuntime {
            val env = OrtEnvironment.getEnvironment()
            val cfg = Json.parseToJsonElement(dir.file("onnx/tts.json").readText()).jsonObject
            val ae = cfg["ae"]!!.jsonObject
            val ttl = cfg["ttl"]!!.jsonObject
            val indexer = Json.parseToJsonElement(dir.file("onnx/unicode_indexer.json").readText()).jsonArray
                .map { it.jsonPrimitive.long }.toLongArray()

            val opened = ArrayList<OrtSession>()
            try {
                fun session(name: String): OrtSession = OrtSession.SessionOptions().use { opts ->
                    opts.setIntraOpNumThreads(intraOpThreads)
                    opts.setInterOpNumThreads(1)
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                    env.createSession(dir.file("onnx/$name.onnx").path, opts).also(opened::add)
                }
                return SupertonicRuntime(
                    env = env,
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
