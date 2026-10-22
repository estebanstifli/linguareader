package com.linguareader.engine.alignment

/**
 * Alineación forzada CTC (Viterbi) sobre log-probabilidades por fotograma.
 * Port a Kotlin del algoritmo de audio.cpp (`mms_forced_aligner/ctc_alignment.cpp`,
 * Apache-2.0, ShugoAI LLC), sin tokens `<star>`: las palabras se separan por blancos.
 */
object CtcForcedAligner {

    class Result(val labelPath: IntArray, val frameLogProbs: FloatArray, val score: Float)

    /**
     * @param logProbs [frames × classes] en orden fila (log-softmax).
     * @param targets ids de etiqueta (sin blancos).
     */
    fun align(logProbs: FloatArray, frames: Int, classes: Int, targets: IntArray, blank: Int): Result {
        require(targets.isNotEmpty()) { "Sin objetivos que alinear" }
        require(targets.none { it == blank || it !in 0 until classes }) { "Objetivo fuera de rango o blanco" }
        val repeats = (1 until targets.size).count { targets[it] == targets[it - 1] }
        require(frames >= targets.size + repeats) { "Audio demasiado corto para el texto ($frames fotogramas, ${targets.size + repeats} necesarios)" }

        val states = 2 * targets.size + 1
        fun label(state: Int) = if (state % 2 == 0) blank else targets[state / 2]
        fun lp(frame: Int, cls: Int) = logProbs[frame * classes + cls]

        val ninf = Float.NEGATIVE_INFINITY
        var prev = FloatArray(states) { ninf }
        var cur = FloatArray(states)
        prev[0] = lp(0, blank)
        prev[1] = lp(0, targets[0])
        // 2 bits por celda: 0 = quedarse, 1 = avanzar uno, 2 = avanzar dos.
        val back = ByteArray(frames * states)
        for (f in 1 until frames) {
            for (s in 0 until states) {
                var best = prev[s]
                var move = 0
                if (s > 0 && prev[s - 1] > best) { best = prev[s - 1]; move = 1 }
                if (s % 2 == 1 && s >= 3 && targets[s / 2] != targets[s / 2 - 1] && prev[s - 2] > best) {
                    best = prev[s - 2]; move = 2
                }
                cur[s] = best + lp(f, label(s))
                back[f * states + s] = move.toByte()
            }
            val t = prev; prev = cur; cur = t
        }
        val finalTarget = states - 2
        val finalBlank = states - 1
        var state = if (prev[finalBlank] > prev[finalTarget]) finalBlank else finalTarget
        val score = maxOf(prev[finalBlank], prev[finalTarget])
        check(score.isFinite()) { "Camino CTC no finito" }

        val path = IntArray(frames)
        val frameLp = FloatArray(frames)
        for (f in frames - 1 downTo 0) {
            val l = label(state)
            path[f] = l
            frameLp[f] = lp(f, l)
            if (f > 0) state -= back[f * states + state].toInt()
        }
        return Result(path, frameLp, score)
    }

    data class Span(val wordIndex: Int, val startFrame: Int, val endFrame: Int, val meanLogProb: Float)

    /**
     * Intervalos por palabra: del primer al último segmento de sus letras (sin incluir silencios).
     */
    fun wordSpans(result: Result, targets: IntArray, targetToWord: IntArray, blank: Int): List<Span> {
        data class Seg(val label: Int, val start: Int, var end: Int)
        val segs = ArrayList<Seg>()
        result.labelPath.forEachIndexed { f, l ->
            if (segs.isEmpty() || segs.last().label != l) segs += Seg(l, f, f) else segs.last().end = f
        }
        val targetSeg = IntArray(targets.size)
        var si = 0
        for (t in targets.indices) {
            while (si < segs.size && segs[si].label != targets[t]) si++
            check(si < segs.size) { "El camino no contiene el objetivo $t" }
            targetSeg[t] = si++
        }
        val spans = ArrayList<Span>()
        var t = 0
        while (t < targets.size) {
            val w = targetToWord[t]
            val firstSeg = targetSeg[t]
            var lastSeg = firstSeg
            while (t + 1 < targets.size && targetToWord[t + 1] == w) { t++; lastSeg = targetSeg[t] }
            t++
            // Límites ajustados a la voz (primera–última letra). audio.cpp consigue lo mismo
            // insertando un token <star> que absorbe el silencio entre palabras; repartir los
            // blancos a la mitad adelantaba ~120 ms el inicio de cada palabra.
            val start = segs[firstSeg].start
            val end = segs[lastSeg].end
            var sum = 0.0
            for (f in start..end) sum += result.frameLogProbs[f]
            spans += Span(w, start, end, (sum / (end - start + 1)).toFloat())
        }
        return spans
    }
}
