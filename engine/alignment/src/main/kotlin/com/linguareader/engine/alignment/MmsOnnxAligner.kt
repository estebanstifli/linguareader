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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Tiempos de una palabra en segundos relativos al clip. */
data class WordTiming(val wordIndex: Int, val startSec: Double, val endSec: Double, val meanLogProb: Float)

/**
 * Alineador forzado MMS-300M (wav2vec2 + CTC) sobre ONNX Runtime: mismo modelo que la familia
 * `mms_forced_aligner` de audio.cpp, exportado a ONNX por onnx-community (int8/q4/fp16).
 * Pesos: CC-BY-NC-4.0 (uso no comercial).
 */
class MmsOnnxAligner private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val text: MmsText,
) : Closeable {

    /** Alinea [words] (en orden) con el audio PCM mono [samples] a [sampleRate] Hz. */
    fun align(samples: FloatArray, sampleRate: Int, words: List<String>): List<WordTiming> {
        val prepared = text.prepare(words)
        if (prepared.targets.isEmpty()) return emptyList()
        val audio = normalize(if (sampleRate == SR) samples else Resampler.resample(samples, sampleRate, SR))
        val logits = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { out ->
                val t = out.get(0) as OnnxTensor
                val shape = t.info.shape // [1, frames, classes]
                val fb = t.floatBuffer
                Triple(FloatArray(fb.remaining()).also { fb.get(it) }, shape[1].toInt(), shape[2].toInt())
            }
        }
        val (data, frames, classes) = logits
        logSoftmaxInPlace(data, frames, classes)
        val result = CtcForcedAligner.align(data, frames, classes, prepared.targets, text.blank)
        val frameSec = FRAME_STRIDE.toDouble() / SR
        return CtcForcedAligner.wordSpans(result, prepared.targets, prepared.targetToWord, text.blank).map {
            WordTiming(it.wordIndex, it.startFrame * frameSec, (it.endFrame + 1) * frameSec, it.meanLogProb)
        }
    }

    override fun close() = session.close()

    companion object {
        const val SR = 16_000
        const val FRAME_STRIDE = 320 // 20 ms por fotograma

        fun load(modelFile: File, vocabFile: File, threads: Int = 4): MmsOnnxAligner {
            val env = OrtEnvironment.getEnvironment()
            val vocab = Json.parseToJsonElement(vocabFile.readText()).jsonObject.mapValues { it.value.jsonPrimitive.int }
            val session = OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(threads)
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(modelFile.path, o)
            }
            return MmsOnnxAligner(env, session, MmsText(vocab))
        }

        /** Wav2Vec2FeatureExtractor(do_normalize=true): media 0 y varianza 1. */
        fun normalize(x: FloatArray): FloatArray {
            val mean = x.average()
            var v = 0.0
            for (s in x) v += (s - mean) * (s - mean)
            val std = sqrt(v / x.size + 1e-7)
            return FloatArray(x.size) { ((x[it] - mean) / std).toFloat() }
        }

        fun logSoftmaxInPlace(d: FloatArray, rows: Int, cols: Int) {
            for (r in 0 until rows) {
                val o = r * cols
                var m = Float.NEGATIVE_INFINITY
                for (c in 0 until cols) if (d[o + c] > m) m = d[o + c]
                var s = 0.0
                for (c in 0 until cols) s += exp((d[o + c] - m).toDouble())
                val lse = m + ln(s).toFloat()
                for (c in 0 until cols) d[o + c] -= lse
            }
        }

        /** Lee un WAV PCM16 mono buscando el bloque "data" (tolera cabeceras con bloques extra, p. ej. de ffmpeg). */
        fun readWav16k(file: File): Pair<FloatArray, Int> {
            val bytes = file.readBytes()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val sr = b.getInt(24)
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = b.getInt(pos + 4)
                if (id == "data") {
                    val n = minOf(size, bytes.size - pos - 8) / 2
                    return FloatArray(n) { b.getShort(pos + 8 + it * 2) / 32768f } to sr
                }
                pos += 8 + size
            }
            error("WAV sin bloque data")
        }

        /** Lee un WAV PCM16 mono (cabecera estándar de 44 bytes, como los que escribe Supertonic). */
        fun readWav(file: File): Pair<FloatArray, Int> {
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val sr = b.getInt(24)
            val n = (file.length().toInt() - 44) / 2
            return FloatArray(n) { b.getShort(44 + it * 2) / 32768f } to sr
        }
    }
}

/** Remuestreo con sinc enventanado (Hann). Suficiente para el frontal de wav2vec2. */
object Resampler {
    fun resample(x: FloatArray, from: Int, to: Int, halfWidth: Int = 16): FloatArray {
        if (from == to) return x
        val ratio = to.toDouble() / from
        val cutoff = minOf(1.0, ratio) * 0.95
        val n = (x.size * ratio).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val center = i / ratio
            val c = center.toInt()
            var acc = 0.0
            var norm = 0.0
            for (k in c - halfWidth + 1..c + halfWidth) {
                if (k < 0 || k >= x.size) continue
                val t = (center - k) * cutoff
                val sinc = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)
                val w = 0.5 + 0.5 * cos(PI * (center - k) / halfWidth)
                val h = sinc * w
                acc += x[k] * h
                norm += h
            }
            out[i] = if (norm == 0.0) 0f else (acc / norm).toFloat()
        }
        return out
    }
}
