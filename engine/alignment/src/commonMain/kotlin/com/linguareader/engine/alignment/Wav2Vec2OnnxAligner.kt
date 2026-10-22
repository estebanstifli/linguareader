package com.linguareader.engine.alignment

import com.linguareader.engine.onnx.OnnxOptLevel
import com.linguareader.engine.onnx.OnnxRuntime
import com.linguareader.engine.onnx.OnnxSession
import com.linguareader.engine.onnx.OnnxSessionOptions
import com.linguareader.engine.onnx.platformOnnxRuntime
import okio.FileSystem
import okio.Path

/**
 * Alineador forzado con un wav2vec2 CTC de caracteres exportado a ONNX (p. ej.
 * facebook/wav2vec2-base-100h, Apache-2.0, solo inglés). Comparte el Viterbi CTC con
 * [MmsOnnxAligner]; solo cambia el tokenizador. Entrada `input_values` [1, muestras] a 16 kHz.
 */
class Wav2Vec2OnnxAligner private constructor(
    private val runtime: OnnxRuntime,
    private val session: OnnxSession,
    private val text: Wav2Vec2Text,
) : AutoCloseable {

    fun align(samples: FloatArray, sampleRate: Int, words: List<String>): List<WordTiming> {
        val prepared = text.prepare(words)
        if (prepared.targets.isEmpty()) return emptyList()
        val sr = MmsOnnxAligner.SR
        val audio = MmsOnnxAligner.normalize(if (sampleRate == sr) samples else Resampler.resample(samples, sampleRate, sr))
        val (data, frames, classes) = MmsOnnxAligner.inferLogits(runtime, session, audio)
        MmsOnnxAligner.logSoftmaxInPlace(data, frames, classes)
        val result = CtcForcedAligner.align(data, frames, classes, prepared.targets, text.blank)
        val frameSec = MmsOnnxAligner.FRAME_STRIDE.toDouble() / sr
        return CtcForcedAligner.wordSpans(result, prepared.targets, prepared.targetToWord, text.blank).map {
            WordTiming(it.wordIndex, it.startFrame * frameSec, (it.endFrame + 1) * frameSec, it.meanLogProb)
        }
    }

    override fun close() = session.close()

    companion object {
        fun load(
            modelFile: Path,
            vocabFile: Path,
            threads: Int = 4,
            runtime: OnnxRuntime = platformOnnxRuntime(),
            fileSystem: FileSystem = FileSystem.SYSTEM,
        ): Wav2Vec2OnnxAligner {
            val vocab = MmsOnnxAligner.readVocab(fileSystem, vocabFile)
            val session = runtime.createSession(modelFile.toString(), OnnxSessionOptions(intraOpThreads = threads, optimization = OnnxOptLevel.ALL))
            return Wav2Vec2OnnxAligner(runtime, session, Wav2Vec2Text(vocab))
        }
    }
}
