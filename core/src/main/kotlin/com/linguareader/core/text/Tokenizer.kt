package com.linguareader.core.text

import com.linguareader.core.model.LanguageTags
import java.text.Normalizer

data class TokenSpan(val start: Int, val end: Int)

/**
 * Tokenizador de palabras consciente de Unicode y diacríticos.
 *
 * - Una palabra es una secuencia de letras/dígitos/marcas combinantes.
 * - Apóstrofos y guiones *internos* forman parte de la palabra ("don't", "grand-mère").
 * - En francés/italiano/catalán se separa la elisión: "l'école" → "l'" + "école",
 *   "qu'est-ce" → "qu'" + "est-ce". "aujourd'hui" no se separa.
 * - La puntuación y los espacios no producen tokens, pero se conservan en el texto.
 */
object Tokenizer {

    private val JOINERS = setOf('\'', '’', '-', '‐', '‑')
    private val APOSTROPHES = setOf('\'', '’')

    private val ELISIONS = mapOf(
        "fr" to setOf("l", "d", "j", "m", "n", "s", "t", "c", "ç", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu"),
        "it" to setOf("l", "d", "c", "un", "dell", "all", "dall", "nell", "sull", "coll", "quest", "quell"),
        "ca" to setOf("l", "d", "m", "n", "s", "t"),
    )

    /** NFC + colapsa espacios ASCII/saltos de línea. Conserva NBSP y puntuación tipográfica. */
    fun normalize(text: String): String {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        return nfc.replace(Regex("[ \\t\\r\\n\\f]+"), " ").trim()
    }

    fun tokenize(text: String, languageTag: String): List<TokenSpan> {
        val raw = scanWords(text)
        val elisions = ELISIONS[LanguageTags.primary(languageTag)] ?: return raw
        return raw.flatMap { splitElisions(text, it, elisions) }
    }

    private fun scanWords(text: String): List<TokenSpan> {
        val spans = mutableListOf<TokenSpan>()
        var i = 0
        var start = -1
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            val isWord = isWordCodePoint(cp)
            if (isWord) {
                if (start < 0) start = i
            } else if (start >= 0) {
                val joiner = len == 1 && text[i] in JOINERS
                val next = i + len
                val nextIsWord = next < text.length && isWordCodePoint(text.codePointAt(next))
                if (!(joiner && nextIsWord)) {
                    spans += TokenSpan(start, i)
                    start = -1
                }
            }
            i += len
        }
        if (start >= 0) spans += TokenSpan(start, text.length)
        return spans
    }

    private fun splitElisions(text: String, span: TokenSpan, elisions: Set<String>): List<TokenSpan> {
        val result = mutableListOf<TokenSpan>()
        var current = span
        while (true) {
            val word = text.substring(current.start, current.end)
            val apostrophe = word.indexOfFirst { it in APOSTROPHES }
            if (apostrophe <= 0) break
            val prefix = word.substring(0, apostrophe).lowercase()
            if (prefix !in elisions) break
            val cut = current.start + apostrophe + 1
            result += TokenSpan(current.start, cut)
            current = TokenSpan(cut, current.end)
        }
        result += current
        return result
    }

    private fun isWordCodePoint(cp: Int): Boolean {
        if (Character.isLetterOrDigit(cp)) return true
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }

    /** Forma canónica para comparar términos: minúsculas, apóstrofo recto, espacios simples. */
    fun matchKey(text: String): String =
        normalize(text).lowercase().replace('’', '\'').replace('‑', '-').replace('‐', '-')
}
