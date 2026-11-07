package com.linguareader.core.alignment

import com.linguareader.core.audio.SpokenMark
import com.linguareader.core.engine.TimedToken
import com.linguareader.core.model.Token

/**
 * Convierte las marcas que da el motor al generar el audio ([SpokenMark]: «el trozo [start, end) del texto hablado
 * empieza a sonar en ms») en tiempos por token. No hace falta alineador: los tiempos son del propio motor.
 *
 * - Cada token se sitúa en el texto hablado por su forma hablada («cuarenta y dos» para «42»), en orden.
 * - El inicio de un token es la primera marca que cae en él; su fin, el inicio del siguiente token marcado. Si entre
 *   los dos hay tokens sin marca, el tramo se reparte por letras para dejarles hueco (luego los rellena [GapFiller]).
 * - Una marca que retrocede en el tiempo se descarta: el resultado es siempre monótono.
 */
object MarkTimings {

    /** Posición de la forma hablada de cada token dentro de [spoken] (null si está vacía o no se encuentra). */
    fun spokenSpans(spoken: String, tokenSpoken: List<String>): List<IntRange?> {
        var cursor = 0
        return tokenSpoken.map { word ->
            val w = word.trim()
            if (w.isEmpty()) return@map null
            val at = spoken.indexOf(w, cursor, ignoreCase = true)
            if (at < 0) null else (at until at + w.length).also { cursor = at + w.length }
        }
    }

    fun toTimed(tokens: List<Token>, spans: List<IntRange?>, marks: List<SpokenMark>, durationMs: Long): List<TimedToken> {
        require(spans.size == tokens.size)
        if (marks.any { it.endMs != null }) return bounded(tokens, spans, marks, durationMs)
        val starts = LongArray(tokens.size) { -1L }
        var from = 0
        var lastMs = -1L
        for (m in marks) {
            if (m.ms < lastMs) continue
            val index = (from until tokens.size).firstOrNull { i ->
                val s = spans[i] ?: return@firstOrNull false
                m.start <= s.last && maxOf(m.end, m.start + 1) > s.first
            } ?: continue
            if (starts[index] < 0) starts[index] = m.ms.coerceIn(0L, durationMs)
            lastMs = m.ms
            from = index
        }
        val marked = tokens.indices.filter { starts[it] >= 0 }
        return marked.mapIndexed { k, i ->
            val next = marked.getOrNull(k + 1)
            val nextStart = next?.let { starts[it] } ?: durationMs
            // Tokens sin marca entre este y el siguiente: se les reserva su parte del tramo.
            val gap = if (next == null) emptyList() else (i + 1 until next).toList()
            val own = letters(tokens[i])
            val total = own + gap.sumOf { letters(tokens[it]) }
            val end = starts[i] + ((nextStart - starts[i]) * own / total.toDouble()).toLong()
            TimedToken(tokens[i].id, starts[i], end.coerceAtLeast(starts[i]), confidence = 1f)
        }
    }

    /**
     * Fronteras acústicas explícitas: no alargarlas hasta el siguiente inicio, ni fabricar límites internos si
     * una marca abarca varios tokens mostrados. Varias palabras habladas de un número sí se pueden reunir en
     * su único token mostrado. Una mezcla de marcas acotadas/no acotadas o tiempos inválidos se rechaza.
     */
    private fun bounded(tokens: List<Token>, spans: List<IntRange?>, marks: List<SpokenMark>, durationMs: Long): List<TimedToken> {
        if (durationMs <= 0 || marks.any { m ->
            m.endMs == null || m.start < 0 || m.end <= m.start || m.ms < 0 || m.endMs <= m.ms || m.endMs > durationMs ||
                m.confidence?.let { !it.isFinite() || it !in 0f..1f } == true
        }) return emptyList()
        if (marks.zipWithNext().any { (a, b) -> b.ms < a.endMs!! || b.start < a.end }) return emptyList()
        val grouped = Array(tokens.size) { mutableListOf<SpokenMark>() }
        for (mark in marks) {
            val matching = spans.indices.filter { i -> spans[i]?.let { mark.start <= it.last && mark.end > it.first } == true }
            if (matching.size == 1) grouped[matching.single()] += mark
        }
        return grouped.mapIndexedNotNull { i, pieces ->
            if (pieces.isEmpty()) null else TimedToken(
                tokens[i].id, pieces.first().ms, pieces.last().endMs!!,
                confidence = pieces.mapNotNull { it.confidence }.minOrNull(),
            )
        }
    }

    private fun letters(token: Token) = token.text.count(Char::isLetterOrDigit).coerceAtLeast(1)
}
