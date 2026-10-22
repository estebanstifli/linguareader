package com.linguareader.core.text

import com.linguareader.core.model.LanguageTags
import com.linguareader.core.platform.sentenceBreaks

/**
 * Divide un párrafo en frases para textos que no vienen segmentados (libros importados).
 *
 * Usa el `BreakIterator` de frases del locale (ICU en Android) y corrige sus fallos típicos:
 * abreviaturas ("Mr.", "Sra.", "p. ej."), iniciales ("J. R. R.") y frases desmesuradas, que se
 * cortan por la puntuación interna más cercana para que el TTS y el resaltado por frase sigan
 * siendo manejables.
 */
object SentenceSegmenter {

    const val MAX_SENTENCE_CHARS = 400

    private val ABBREVIATIONS = mapOf(
        "en" to setOf("mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "e.g", "i.e", "no", "vol", "fig", "mt", "capt", "col", "gen", "lt", "rev", "sgt"),
        "es" to setOf("sr", "sra", "srta", "dr", "dra", "d", "dª", "dña", "ud", "uds", "vd", "vds", "p. ej", "ej", "etc", "pág", "págs", "núm", "art", "cap", "aprox", "tel", "av", "avda", "sto", "sta"),
        "fr" to setOf("m", "mm", "mme", "mmes", "mlle", "mlles", "dr", "pr", "st", "ste", "etc", "p. ex", "cf", "av", "bd", "chap", "vol", "env"),
        "de" to setOf("hr", "fr", "dr", "prof", "z. b", "z.b", "bzw", "usw", "ca", "vgl", "nr", "str", "s", "d. h", "u. a"),
        "it" to setOf("sig", "sigg", "sig.ra", "dott", "prof", "ecc", "es", "p. es", "pag", "cap", "nr"),
        "pt" to setOf("sr", "sra", "dr", "dra", "prof", "etc", "p. ex", "pág", "cap", "nº"),
    )

    /** Frases de [paragraph] ya normalizadas, sin vacías. Cada frase conserva su puntuación final. */
    fun split(paragraph: String, languageTag: String): List<String> {
        val text = Tokenizer.normalize(paragraph)
        if (text.isEmpty()) return emptyList()
        val primary = LanguageTags.primary(languageTag)
        val raw = mutableListOf<String>()
        var start = 0
        for (end in sentenceBreaks(text, languageTag)) {
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(raw::add)
            start = end
        }

        val abbreviations = ABBREVIATIONS[primary].orEmpty()
        val merged = mutableListOf<String>()
        for (piece in raw) {
            val previous = merged.lastOrNull()
            if (previous != null && (endsWithAbbreviation(previous, abbreviations) || startsLowercase(piece))) {
                merged[merged.lastIndex] = "$previous $piece"
            } else {
                merged += piece
            }
        }
        return merged.flatMap(::capLength)
    }

    private fun endsWithAbbreviation(sentence: String, abbreviations: Set<String>): Boolean {
        if (!sentence.endsWith('.')) return false
        val body = sentence.dropLast(1)
        val lastWord = body.substringAfterLast(' ')
        // Número suelto con punto: dirección («156. Charing Cross Road»), elemento de lista.
        if (sentence == "$lastWord." && lastWord.isNotEmpty() && lastWord.all(Char::isDigit)) return true
        // Inicial: "J." o "R." (una sola letra mayúscula).
        if (lastWord.length == 1 && lastWord[0].isUpperCase()) return true
        val lower = body.lowercase()
        return abbreviations.any { abbr ->
            lower.endsWith(abbr) && (lower.length == abbr.length || !lower[lower.length - abbr.length - 1].isLetter())
        }
    }

    /** Un corte seguido de minúscula casi nunca es un final de frase real ("p. ej. una", "…dijo él. y"). */
    private fun startsLowercase(piece: String): Boolean {
        val first = piece.firstOrNull { it.isLetter() } ?: return false
        val firstIndex = piece.indexOf(first)
        // Las aperturas de diálogo/cita ("—", "«", "¿") no cuentan como minúscula de continuación.
        return first.isLowerCase() && piece.substring(0, firstIndex).none { it in "—–-«“\"¿¡(" }
    }

    private fun capLength(sentence: String): List<String> {
        if (sentence.length <= MAX_SENTENCE_CHARS) return listOf(sentence)
        val parts = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX_SENTENCE_CHARS) {
            val window = rest.substring(0, MAX_SENTENCE_CHARS)
            val cut = listOf(";", ":", ",", " —", " ")
                .firstNotNullOfOrNull { mark -> window.lastIndexOf(mark).takeIf { it > MAX_SENTENCE_CHARS / 3 } }
                ?.let { it + 1 }
                ?: MAX_SENTENCE_CHARS
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }
}
