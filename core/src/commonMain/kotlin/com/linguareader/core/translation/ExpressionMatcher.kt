package com.linguareader.core.translation

import com.linguareader.core.model.GlossaryEntry
import com.linguareader.core.model.Passage
import com.linguareader.core.model.TermKind
import com.linguareader.core.model.Token
import com.linguareader.core.text.Tokenizer

data class GlossaryMatch(
    val entry: GlossaryEntry,
    /** Índices de token (inclusive) dentro del pasaje. */
    val firstTokenIndex: Int,
    val lastTokenIndex: Int,
    /** Texto exacto del pasaje que cubre la coincidencia. */
    val surface: String,
) {
    val tokenCount: Int get() = lastTokenIndex - firstTokenIndex + 1
    val isMultiToken: Boolean get() = tokenCount > 1
}

/**
 * Busca en el glosario del libro la palabra tocada y la expresión más larga que la contiene
 * (phrasal verbs, contracciones con elisión, locuciones). Token y expresión pueden diferir:
 * tocar "up" en "wakes up" devuelve el token "up" y la expresión "wake up".
 *
 * Limitación conocida: solo detecta expresiones contiguas dentro de la misma frase
 * ("wake him up" no se reconoce como "wake up").
 */
class ExpressionMatcher(glossary: List<GlossaryEntry>, private val maxTokens: Int = 6) {

    private val byForm: Map<String, List<GlossaryEntry>> =
        glossary.flatMap { entry -> entry.forms.map { it to entry } }
            .groupBy({ it.first }, { it.second })

    fun wordEntry(passage: Passage, token: Token): GlossaryMatch? {
        val key = Tokenizer.matchKey(token.text)
        val entry = byForm[key]?.firstOrNull() ?: return null
        return GlossaryMatch(entry, token.index, token.index, token.text)
    }

    /** Expresión de varios tokens más larga que contiene al token, o null. */
    fun expressionFor(passage: Passage, token: Token): GlossaryMatch? {
        val sentenceTokens = passage.tokens.filter { it.sentenceId == token.sentenceId }
        val pos = sentenceTokens.indexOfFirst { it.id == token.id }
        if (pos < 0) return null
        var best: GlossaryMatch? = null
        for (start in maxOf(0, pos - maxTokens + 1)..pos) {
            for (end in pos until minOf(sentenceTokens.size, start + maxTokens)) {
                if (end == start) continue
                val first = sentenceTokens[start]
                val last = sentenceTokens[end]
                val surface = passage.text.substring(first.rangeStart, last.rangeEnd)
                val entry = byForm[Tokenizer.matchKey(surface)]?.firstOrNull() ?: continue
                val candidate = GlossaryMatch(entry, first.index, last.index, surface)
                if (best == null || candidate.tokenCount > best.tokenCount) best = candidate
            }
        }
        // Una contracción de un solo token ("don't") también se presenta como expresión.
        if (best == null) {
            val single = wordEntry(passage, token)
            if (single != null && single.entry.kind != TermKind.WORD) return single
        }
        return best
    }
}
