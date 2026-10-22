package com.linguareader.core.translation

import com.linguareader.core.engine.ContentRef
import com.linguareader.core.engine.TranslationEngine
import com.linguareader.core.engine.TranslationRequest
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.engine.TranslationUnit
import com.linguareader.core.model.Book
import com.linguareader.core.model.Passage
import com.linguareader.core.model.Sentence
import com.linguareader.core.model.Token

data class WordLookup(
    val token: Token,
    val sentence: Sentence,
    val word: TranslationResult,
    val wordLemma: String?,
    val expression: GlossaryMatch?,
    val expressionTranslation: TranslationResult?,
    val sentenceTranslation: TranslationResult,
)

/** Orquesta la ficha de palabra: palabra + expresión que la contiene + frase, con contexto. */
class WordLookupService(private val engine: TranslationEngine) {

    suspend fun lookup(book: Book, passage: Passage, token: Token, targetLanguage: String): WordLookup {
        val matcher = ExpressionMatcher(book.glossary)
        val sentence = passage.sentenceById(token.sentenceId)
            ?: error("Token ${token.id} sin frase")
        val contextBefore = passage.text.substring(sentence.rangeStart, token.rangeStart)
        val contextAfter = passage.text.substring(token.rangeEnd, sentence.rangeEnd)

        val wordMatch = matcher.wordEntry(passage, token)
        val word = engine.translate(
            TranslationRequest(
                text = token.text,
                contextBefore = contextBefore,
                contextAfter = contextAfter,
                sourceLanguage = book.learningLanguage,
                targetLanguage = targetLanguage,
                unit = TranslationUnit.WORD,
                ref = ContentRef(book.id, passage.id, sentence.id, wordMatch?.entry?.id),
            ),
        )

        val expression = matcher.expressionFor(passage, token)
        val expressionTranslation = expression?.let { match ->
            val first = passage.tokens[match.firstTokenIndex]
            val last = passage.tokens[match.lastTokenIndex]
            engine.translate(
                TranslationRequest(
                    text = match.surface,
                    contextBefore = passage.text.substring(sentence.rangeStart, first.rangeStart),
                    contextAfter = passage.text.substring(last.rangeEnd, sentence.rangeEnd),
                    sourceLanguage = book.learningLanguage,
                    targetLanguage = targetLanguage,
                    unit = TranslationUnit.EXPRESSION,
                    ref = ContentRef(book.id, passage.id, sentence.id, match.entry.id),
                ),
            )
        }

        return WordLookup(
            token = token,
            sentence = sentence,
            word = word,
            wordLemma = wordMatch?.entry?.lemma,
            expression = expression,
            expressionTranslation = expressionTranslation,
            sentenceTranslation = translateSentence(book, passage, sentence, targetLanguage),
        )
    }

    suspend fun translateSentence(book: Book, passage: Passage, sentence: Sentence, targetLanguage: String): TranslationResult {
        val previous = passage.sentences.getOrNull(sentence.index - 1)?.text.orEmpty()
        val next = passage.sentences.getOrNull(sentence.index + 1)?.text.orEmpty()
        return engine.translate(
            TranslationRequest(
                text = sentence.text,
                contextBefore = previous,
                contextAfter = next,
                sourceLanguage = book.learningLanguage,
                targetLanguage = targetLanguage,
                unit = TranslationUnit.SENTENCE,
                ref = ContentRef(book.id, passage.id, sentence.id),
            ),
        )
    }
}
