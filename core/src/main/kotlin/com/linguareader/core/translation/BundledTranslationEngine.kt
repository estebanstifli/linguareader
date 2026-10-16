package com.linguareader.core.translation

import com.linguareader.core.engine.BookRepository
import com.linguareader.core.engine.TranslatedExample
import com.linguareader.core.engine.TranslationEngine
import com.linguareader.core.engine.TranslationRequest
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.engine.TranslationResult.Reason
import com.linguareader.core.engine.TranslationUnit
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.text.Tokenizer

/**
 * Proveedor offline que solo usa datos incluidos en el pack: traducciones de frase del manifiesto
 * y glosario del libro. Si no hay dato, devuelve [TranslationResult.Unavailable]; nunca inventa.
 */
class BundledTranslationEngine(private val books: BookRepository) : TranslationEngine {

    override val id: String = "bundled"

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        if (LanguageTags.sameLanguage(request.sourceLanguage, request.targetLanguage)) {
            return TranslationResult.Unavailable(Reason.SAME_LANGUAGE, id)
        }
        val ref = request.ref ?: return TranslationResult.Unavailable(Reason.NO_ENTRY, id)
        val book = runCatching { books.loadBook(ref.bookId) }.getOrNull()
            ?: return TranslationResult.Unavailable(Reason.NO_ENTRY, id)
        val target = request.targetLanguage

        return when (request.unit) {
            TranslationUnit.SENTENCE -> {
                val sentence = ref.passageId?.let { pid -> book.passages.firstOrNull { it.id == pid } }
                    ?.let { passage -> ref.sentenceId?.let(passage::sentenceById) }
                val text = sentence?.let { LanguageTags.lookup(it.translations, target) }
                if (text != null) TranslationResult.Translated(text, id, contextual = true)
                else TranslationResult.Unavailable(Reason.NO_ENTRY, id)
            }
            TranslationUnit.WORD, TranslationUnit.EXPRESSION -> {
                val entry = ref.glossaryEntryId?.let { gid -> book.glossary.firstOrNull { it.id == gid } }
                    ?: Tokenizer.matchKey(request.text).let { key -> book.glossary.firstOrNull { key in it.forms } }
                    ?: return TranslationResult.Unavailable(Reason.NO_ENTRY, id)
                val text = LanguageTags.lookup(entry.translations, target)
                    ?: return TranslationResult.Unavailable(Reason.NO_ENTRY, id)
                TranslationResult.Translated(
                    text = text,
                    providerId = id,
                    lemma = entry.lemma,
                    partOfSpeech = entry.partOfSpeech,
                    examples = entry.examples.map { TranslatedExample(it.text, LanguageTags.lookup(it.translations, target)) },
                    contextual = false,
                )
            }
        }
    }
}

/** Encadena proveedores: devuelve el primer resultado traducido; si ninguno, el último "no disponible". */
class FallbackTranslationEngine(private val engines: List<TranslationEngine>) : TranslationEngine {
    init { require(engines.isNotEmpty()) }

    override val id: String = engines.joinToString("+") { it.id }

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        var last: TranslationResult = TranslationResult.Unavailable(Reason.NO_ENTRY, id)
        for (engine in engines) {
            val result = runCatching { engine.translate(request) }
                .getOrElse { TranslationResult.Unavailable(Reason.ENGINE_ERROR, engine.id) }
            if (result is TranslationResult.Translated) return result
            last = result
        }
        return last
    }
}
