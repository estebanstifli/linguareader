package com.linguareader.core.alignment

import com.linguareader.core.engine.TimedToken
import com.linguareader.core.model.Passage

/** Calidad de una alineación: decide si el lector puede resaltar por palabra o solo por frase. */
sealed interface AlignmentQuality {
    data object WordLevel : AlignmentQuality
    data class SentenceOnly(val reasons: List<String>) : AlignmentQuality
}

/**
 * Valida tiempos por palabra antes de usarlos. Se rechazan (→ resaltado por frase) si:
 * no son monótonos, tienen intervalos inválidos, no cubren suficientes tokens del pasaje,
 * referencian tokens inexistentes o su confianza media es baja.
 */
object AlignmentValidator {

    fun validate(
        passage: Passage,
        timed: List<TimedToken>,
        minCoverage: Double = 0.9,
        minMeanConfidence: Float = 0.5f,
    ): AlignmentQuality {
        val reasons = mutableListOf<String>()
        if (timed.isEmpty()) return AlignmentQuality.SentenceOnly(listOf("sin tiempos"))

        val tokenIndex = passage.tokens.associate { it.id to it.index }
        if (timed.any { it.tokenId !in tokenIndex }) reasons += "tokens desconocidos"
        if (timed.any { it.startMs < 0 || it.endMs < it.startMs }) reasons += "intervalos inválidos"

        val monotonic = timed.zipWithNext().all { (a, b) ->
            b.startMs >= a.startMs && (tokenIndex[b.tokenId] ?: -1) > (tokenIndex[a.tokenId] ?: -1)
        }
        if (!monotonic) reasons += "no monótona"

        val coverage = timed.map { it.tokenId }.toSet().size.toDouble() / passage.tokens.size.coerceAtLeast(1)
        if (coverage < minCoverage) reasons += "cobertura ${"%.0f".format(coverage * 100)}% < ${(minCoverage * 100).toInt()}%"

        val confidences = timed.mapNotNull { it.confidence }
        if (confidences.isNotEmpty() && confidences.average() < minMeanConfidence) reasons += "confianza baja"

        return if (reasons.isEmpty()) AlignmentQuality.WordLevel else AlignmentQuality.SentenceOnly(reasons)
    }
}

/** Línea temporal validada: correlaciona la posición del reproductor con el token activo en O(log n). */
class AlignmentTimeline(private val timed: List<TimedToken>) {

    private val starts = LongArray(timed.size) { timed[it].startMs }

    /** Token cuyo intervalo contiene [positionMs]; en huecos entre palabras devuelve el anterior. */
    fun tokenAt(positionMs: Long): TimedToken? {
        if (timed.isEmpty() || positionMs < starts[0]) return null
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= positionMs) lo = mid else hi = mid - 1
        }
        val candidate = timed[lo]
        val isLast = lo == timed.lastIndex
        return if (isLast && positionMs > candidate.endMs) null else candidate
    }
}
