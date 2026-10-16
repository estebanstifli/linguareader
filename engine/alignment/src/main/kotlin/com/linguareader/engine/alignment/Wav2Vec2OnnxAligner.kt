package com.linguareader.engine.alignment

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * Alineador forzado con un wav2vec2 CTC de caracteres exportado a ONNX (p. ej.
 * facebook/wav2vec2-base-100h, Apache-2.0, solo inglés). Comparte el Viterbi CTC con
 * [MmsOnnxAligner]; solo cambia el tokenizador. Entrada `input_values` [1, muestras] a 16 kHz.
 */
class Wav2Vec2OnnxAligner private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val text: Wav2Vec2Text,
) : Closeable {

    fun align(samples: FloatArray, sampleRate: Int, words: List<String>): List<WordTiming> {
        val prepared = text.prepare(words)
        if (prepared.targets.isEmpty()) return emptyList()
        val sr = MmsOnnxAligner.SR
        val audio = MmsOnnxAligner.normalize(if (sampleRate == sr) samples else Resampler.resample(samples, sampleRate, sr))
        val (data, frames, classes) = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { out ->
                val t = out.get(0) as OnnxTensor
                val shape = t.info.shape
                val fb = t.floatBuffer
                Triple(FloatArray(fb.remaining()).also { fb.get(it) }, shape[1].toInt(), shape[2].toInt())
            }
        }
        MmsOnnxAligner.logSoftmaxInPlace(data, frames, classes)
        val result = CtcForcedAligner.align(data, frames, classes, prepared.targets, text.blank)
        val frameSec = MmsOnnxAligner.FRAME_STRIDE.toDouble() / sr
        return CtcForcedAligner.wordSpans(result, prepared.targets, prepared.targetToWord, text.blank).map {
            WordTiming(it.wordIndex, it.startFrame * frameSec, (it.endFrame + 1) * frameSec, it.meanLogProb)
        }
    }

    override fun close() = session.close()

    companion object {
        fun load(modelFile: File, vocabFile: File, threads: Int = 4): Wav2Vec2OnnxAligner {
            val env = OrtEnvironment.getEnvironment()
            val vocab = Json.parseToJsonElement(vocabFile.readText()).jsonObject.mapValues { it.value.jsonPrimitive.int }
            val session = OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(threads)
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(modelFile.path, o)
            }
            return Wav2Vec2OnnxAligner(env, session, Wav2Vec2Text(vocab))
        }
    }
}
