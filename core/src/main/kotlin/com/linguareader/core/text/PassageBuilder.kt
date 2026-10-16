package com.linguareader.core.text

import com.linguareader.core.model.MediaRef
import com.linguareader.core.model.Passage
import com.linguareader.core.model.Sentence
import com.linguareader.core.model.Token

data class SentenceInput(
    val id: String,
    val text: String,
    val translations: Map<String, String>,
)

/**
 * Construye un [Passage] con texto normalizado e inmutable y tokens con identificadores
 * estables: `"<passageId>:t<n>"`. Mientras el texto del manifiesto no cambie, los ids tampoco.
 */
object PassageBuilder {

    const val SENTENCE_SEPARATOR = " "

    fun build(
        passageId: String,
        chapterId: String,
        globalIndex: Int,
        languageTag: String,
        sentences: List<SentenceInput>,
        image: MediaRef?,
    ): Passage {
        require(sentences.isNotEmpty()) { "El pasaje $passageId no tiene frases" }
        val text = StringBuilder()
        val builtSentences = mutableListOf<Sentence>()
        val tokens = mutableListOf<Token>()

        sentences.forEachIndexed { index, input ->
            if (index > 0) text.append(SENTENCE_SEPARATOR)
            val normalized = Tokenizer.normalize(input.text)
            require(normalized.isNotEmpty()) { "Frase vacía ${input.id} en $passageId" }
            val start = text.length
            text.append(normalized)
            val end = text.length
            builtSentences += Sentence(
                id = input.id,
                index = index,
                text = normalized,
                rangeStart = start,
                rangeEnd = end,
                translations = input.translations.mapValues { Tokenizer.normalize(it.value) },
            )
            Tokenizer.tokenize(normalized, languageTag).forEach { span ->
                tokens += Token(
                    id = "$passageId:t${tokens.size}",
                    index = tokens.size,
                    text = normalized.substring(span.start, span.end),
                    rangeStart = start + span.start,
                    rangeEnd = start + span.end,
                    sentenceId = input.id,
                )
            }
        }
        return Passage(
            id = passageId,
            chapterId = chapterId,
            index = globalIndex,
            text = text.toString(),
            sentences = builtSentences,
            tokens = tokens,
            image = image,
        )
    }
}

/** Búsquedas por offset en O(log n) sobre listas ordenadas por rango. */
object OffsetIndex {

    /** Token que contiene [offset], o null si cae en espacio/puntuación. */
    fun tokenAt(tokens: List<Token>, offset: Int): Token? {
        var lo = 0
        var hi = tokens.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val t = tokens[mid]
            when {
                offset < t.rangeStart -> hi = mid - 1
                offset >= t.rangeEnd -> lo = mid + 1
                else -> return t
            }
        }
        return null
    }

    /** Primer token que intersecta [start, end), útil para eventos de rango del TTS. */
    fun firstTokenIn(tokens: List<Token>, start: Int, end: Int): Token? {
        var lo = 0
        var hi = tokens.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tokens[mid].rangeEnd <= start) lo = mid + 1 else hi = mid
        }
        val candidate = tokens.getOrNull(lo) ?: return null
        return if (candidate.rangeStart < end) candidate else null
    }

    fun sentenceAt(sentences: List<Sentence>, offset: Int): Sentence? =
        sentences.firstOrNull { offset >= it.rangeStart && offset < it.rangeEnd }
}
