package com.linguareader.core.narration

import com.linguareader.core.alignment.AlignmentTimeline
import com.linguareader.core.engine.TimedToken
import com.linguareader.core.model.Book
import com.linguareader.core.model.Token
import java.text.Normalizer
import java.util.Locale

/** Narración pregrabada de un libro: una pista de audio continua y los tiempos de cada palabra del texto. */
class Narration(
    val bookId: String,
    /** Uri que entiende el reproductor (`asset:///…`, `file://…` o ruta de fichero). */
    val audioUri: String,
    val timeline: NarrationTimeline,
)

/** Activa (o quita, con null) la narración pregrabada del libro abierto. */
interface NarrationSelector {
    suspend fun setNarration(narration: Narration?)
}

/**
 * Tiempos de todas las palabras del libro sobre la pista de narración (ms absolutos), en orden de lectura.
 * Los tokens que la narración no permitió casar llevan `estimated = true` (hueco repartido entre sus vecinos).
 */
class NarrationTimeline(
    val words: List<TimedToken>,
    private val passageOf: Map<String, Int>,
    /** Tokens del libro casados con una palabra de la narración. */
    val matchedTokens: Int,
    val totalTokens: Int,
    /** Palabras de la narración que no se encontraron en el texto. */
    val unmatchedNarrationWords: Int,
) {
    private val timeline = AlignmentTimeline(words)
    private val byToken = words.associateBy { it.tokenId }
    private val ranges: Map<Int, LongRange> = buildMap {
        for (w in words) {
            val p = passageOf[w.tokenId] ?: continue
            val r = get(p)
            put(p, if (r == null) w.startMs..w.endMs else minOf(r.first, w.startMs)..maxOf(r.last, w.endMs))
        }
    }
    private val passageStarts = ranges.entries.sortedBy { it.key }

    val coverage: Double get() = matchedTokens.toDouble() / totalTokens.coerceAtLeast(1)

    fun tokenAt(positionMs: Long): TimedToken? = timeline.tokenAt(positionMs)
    fun timeOf(tokenId: String): TimedToken? = byToken[tokenId]
    fun passageIndexOf(tokenId: String): Int? = passageOf[tokenId]

    /** Desde el inicio de la primera palabra hasta el fin de la última del pasaje; null si no tiene ninguna. */
    fun passageRange(passageIndex: Int): LongRange? = ranges[passageIndex]

    /** Inicio del primer pasaje con tiempos a partir de [passageIndex] (incluido). */
    fun startFrom(passageIndex: Int): Long? = passageStarts.firstOrNull { it.key >= passageIndex }?.value?.first
}

/**
 * Casa las palabras de una narración (p. ej. de un ASS de karaoke) con los tokens del libro. El texto es el mismo,
 * pero no carácter a carácter: puntuación, comillas, mayúsculas y alguna palabra partida o unida (elisiones,
 * guiones) pueden diferir. Se comparan formas plegadas (minúsculas, sin diacríticos, solo letras y dígitos), se
 * admite que una palabra corresponda a 2–3 tokens o al revés, y si algo no casa se busca el siguiente punto en que
 * vuelvan a coincidir tres palabras seguidas (palabras omitidas o añadidas por la narración).
 */
object NarrationAligner {

    private const val WINDOW = 40
    private const val ANCHOR = 3

    fun align(book: Book, narration: List<TimedWord>): NarrationTimeline {
        val tokens = mutableListOf<Token>()
        val passageOf = HashMap<String, Int>()
        for (p in book.passages) for (t in p.tokens) {
            if (fold(t.text).isEmpty()) continue
            tokens += t
            passageOf[t.id] = p.index
        }
        val words = narration.filter { fold(it.text).isNotEmpty() }
        val tk = tokens.map { fold(it.text) }
        val wk = words.map { fold(it.text) }

        val start = LongArray(tokens.size) { -1 }
        val end = LongArray(tokens.size) { -1 }
        var i = 0
        var j = 0
        var unmatchedWords = 0
        while (i < wk.size && j < tk.size) {
            val m = matchAt(wk, tk, i, j)
            if (m != null) {
                assign(m, i, j, words, tk, start, end)
                i += m.first
                j += m.second
                continue
            }
            val skip = resync(wk, tk, i, j)
            if (skip != null) {
                unmatchedWords += skip.first
                i += skip.first
                j += skip.second
            } else {
                // Sin punto de reencuentro cercano: se trata como una palabra cambiada.
                unmatchedWords++
                i++
                j++
            }
        }
        unmatchedWords += wk.size - i
        val matched = BooleanArray(tokens.size) { start[it] >= 0 }
        fillGaps(start, end, matched)

        val timed = ArrayList<TimedToken>(tokens.size)
        var floor = 0L
        for (k in tokens.indices) {
            val s = maxOf(start[k], floor)
            val e = maxOf(end[k], s)
            timed += TimedToken(tokens[k].id, s, e, estimated = !matched[k])
            floor = s
        }
        return NarrationTimeline(timed, passageOf, matched.count { it }, tokens.size, unmatchedWords)
    }

    /** Forma de comparación: minúsculas, sin diacríticos, solo letras y dígitos. */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }

    /** (palabras, tokens) que consume la coincidencia en (i, j), o null. */
    private fun matchAt(w: List<String>, t: List<String>, i: Int, j: Int): Pair<Int, Int>? {
        if (i >= w.size || j >= t.size) return null
        if (same(w[i], t[j])) return 1 to 1
        var joined = t[j]
        for (k in 2..3) {
            if (j + k - 1 >= t.size) break
            joined += t[j + k - 1]
            if (joined.length > w[i].length) break
            if (joined == w[i]) return 1 to k
        }
        joined = w[i]
        for (k in 2..3) {
            if (i + k - 1 >= w.size) break
            joined += w[i + k - 1]
            if (joined.length > t[j].length) break
            if (joined == t[j]) return k to 1
        }
        return null
    }

    /**
     * Misma palabra con otra grafía: letras repetidas («Mmm» / «Mmmm»), variantes como «grey» / «gray» o
     * «favourite» / «favorite» (una edición en palabras de 4+ letras, dos en las de 8+).
     */
    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        if (squeeze(a) == squeeze(b)) return true
        val n = minOf(a.length, b.length)
        val allowed = when { n >= 8 -> 2; n >= 4 -> 1; else -> 0 }
        return allowed > 0 && kotlin.math.abs(a.length - b.length) <= allowed && editDistance(a, b, allowed) <= allowed
    }

    private fun squeeze(s: String) = buildString { for (c in s) if (isEmpty() || last() != c) append(c) }

    /** Distancia de Levenshtein, acotada: devuelve [limit] + 1 en cuanto se supera. */
    private fun editDistance(a: String, b: String, limit: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        for (x in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = x
            var best = cur[0]
            for (y in 1..b.length) {
                cur[y] = minOf(prev[y] + 1, cur[y - 1] + 1, prev[y - 1] + if (a[x - 1] == b[y - 1]) 0 else 1)
                best = minOf(best, cur[y])
            }
            if (best > limit) return limit + 1
            prev = cur
        }
        return prev[b.length]
    }

    /** ¿Coinciden [ANCHOR] palabras seguidas a partir de (i, j)? */
    private fun anchored(w: List<String>, t: List<String>, i: Int, j: Int): Boolean {
        var a = i
        var b = j
        repeat(ANCHOR) {
            val m = matchAt(w, t, a, b) ?: return a >= w.size || b >= t.size
            a += m.first
            b += m.second
        }
        return true
    }

    /** Menor salto (palabras, tokens) tras el que vuelven a coincidir [ANCHOR] palabras. */
    private fun resync(w: List<String>, t: List<String>, i: Int, j: Int): Pair<Int, Int>? {
        for (total in 1..2 * WINDOW) {
            for (di in 0..minOf(total, WINDOW)) {
                val dj = total - di
                if (dj > WINDOW) continue
                if (i + di < w.size && j + dj < t.size && anchored(w, t, i + di, j + dj)) return di to dj
            }
        }
        return null
    }

    private fun assign(m: Pair<Int, Int>, i: Int, j: Int, words: List<TimedWord>, tk: List<String>, start: LongArray, end: LongArray) {
        val (nw, nt) = m
        if (nt == 1) {
            start[j] = words[i].startMs
            end[j] = words[i + nw - 1].endMs
            return
        }
        // Una palabra = varios tokens: su tiempo se reparte según las letras de cada uno.
        val w = words[i]
        val letters = (j until j + nt).sumOf { tk[it].length }.coerceAtLeast(1)
        var t = w.startMs.toDouble()
        val per = (w.endMs - w.startMs).coerceAtLeast(0) / letters.toDouble()
        for (k in j until j + nt) {
            start[k] = t.toLong()
            t += tk[k].length * per
            end[k] = t.toLong()
        }
    }

    /** Reparte el hueco entre las palabras casadas vecinas entre los tokens sin tiempo. */
    private fun fillGaps(start: LongArray, end: LongArray, matched: BooleanArray) {
        var k = 0
        while (k < start.size) {
            if (matched[k]) { k++; continue }
            val from = k
            while (k < start.size && !matched[k]) k++
            val before = if (from > 0) end[from - 1] else (if (k < start.size) start[k] else 0L)
            val after = if (k < start.size) start[k] else before
            val span = (after - before).coerceAtLeast(0)
            val n = k - from
            for (x in 0 until n) {
                start[from + x] = before + span * x / n
                end[from + x] = before + span * (x + 1) / n
            }
        }
    }
}
