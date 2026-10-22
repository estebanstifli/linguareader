package com.linguareader.importer.layout

import com.linguareader.core.model.Book
import com.linguareader.core.platform.UnicodeForm
import com.linguareader.core.platform.normalizeUnicode

/**
 * Palabra tal como está dibujada en una página del documento original, con su caja en
 * fracciones del ancho y alto de la página (0..1, origen arriba a la izquierda). Así sirve
 * para cualquier escala de renderizado y de zoom.
 */
data class PageWord(
    val page: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Caja de un token del libro sobre una página del original (un token partido entre líneas tiene dos). */
data class TokenBox(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Relaciona los tokens del libro importado (texto reconstruido: sin cabeceras ni números de
 * página, con las palabras partidas por guion ya unidas) con las palabras dibujadas en el
 * original, para resaltar y tocar sobre las páginas.
 *
 * Ambas secuencias salen del mismo texto en el mismo orden, así que basta un emparejamiento
 * voraz con ventana: cada token busca su palabra entre las siguientes [window] del original.
 * Lo que solo está en una de las dos (cabeceras, pies, números, guiones) se salta sin perder
 * la sincronía. Un token sin pareja queda sin caja: no se inventa su posición.
 */
object WordBoxAligner {

    /** Cajas por id de token, en el orden de lectura del libro. */
    fun align(book: Book, words: List<PageWord>, window: Int = 40): Map<String, List<TokenBox>> {
        val pdf = words.mapNotNull { w -> normalize(w.text).takeIf { it.isNotEmpty() }?.let { it to w } }
        val tokens = book.passages.flatMap { p -> p.tokens }.mapNotNull { t -> normalize(t.text).takeIf { it.isNotEmpty() }?.let { t.id to it } }
        val out = LinkedHashMap<String, List<TokenBox>>()

        /** true si los tokens que siguen a [i] aparecen justo después de la palabra [k]: confirma un salto largo. */
        fun confirmed(i: Int, k: Int): Boolean {
            var matched = 0
            var at = k + 1
            for (n in i + 1..minOf(tokens.lastIndex, i + 3)) {
                val next = tokens[n].second
                val found = (at until minOf(pdf.size, at + 3)).firstOrNull { pdf[it].first == next } ?: continue
                matched++
                at = found + 1
            }
            return matched >= minOf(2, tokens.lastIndex - i)
        }

        var j = 0
        for ((i, entry) in tokens.withIndex()) {
            val (id, target) = entry
            if (j >= pdf.size) break
            val end = minOf(pdf.size, j + window)
            var k = j
            while (k < end) {
                val text = pdf[k].first
                // Fuera de la palabra siguiente, la coincidencia solo vale si la confirman las que siguen: una palabra
                // frecuente («the», «de») más adelante no debe arrastrar la sincronía y dejar sin caja lo de en medio.
                val near = k == j
                if (text == target && (near || confirmed(i, k))) {
                    out[id] = listOf(pdf[k].second.box())
                    j = k + 1
                    break
                }
                // Palabra partida al final de línea o de página («travel-» + «led»).
                if (k + 1 < pdf.size && target.length > text.length && target.startsWith(text) && text + pdf[k + 1].first == target &&
                    (near || confirmed(i, k + 1))
                ) {
                    out[id] = listOf(pdf[k].second.box(), pdf[k + 1].second.box())
                    j = k + 2
                    break
                }
                k++
            }
        }
        return out
    }

    /** Solo letras y cifras, en minúscula y compatibilidad Unicode (ligaduras «ﬁ» → «fi»). */
    fun normalize(text: String): String {
        val folded = normalizeUnicode(text, UnicodeForm.NFKC).lowercase()
        return buildString(folded.length) { folded.forEach { if (it.isLetterOrDigit()) append(it) } }
    }

    private fun PageWord.box() = TokenBox(page, left, top, right, bottom)
}
