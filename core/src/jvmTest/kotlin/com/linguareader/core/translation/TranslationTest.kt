package com.linguareader.core.translation

import com.linguareader.core.engine.TranslationEngine
import com.linguareader.core.engine.TranslationRequest
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.engine.TranslationResult.Reason
import com.linguareader.core.model.TermKind
import com.linguareader.core.testing.InMemoryBookRepository
import com.linguareader.core.testing.SampleBooks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTest {

    private val en = SampleBooks.english
    private val fr = SampleBooks.french
    private val engine = BundledTranslationEngine(InMemoryBookRepository(en, fr))
    private val lookup = WordLookupService(engine)

    private fun tokenOf(book: com.linguareader.core.model.Book, text: String) =
        book.passages.first().tokens.first { it.text == text }

    @Test
    fun `tapping particle of phrasal verb returns token and expression`() = runTest {
        val passage = en.passages.first()
        val result = lookup.lookup(en, passage, tokenOf(en, "up"), "es")
        assertEquals("up", result.token.text)
        assertEquals("wakes up", result.expression?.surface)
        assertEquals(TermKind.PHRASAL_VERB, result.expression?.entry?.kind)
        assertEquals("despertarse", (result.expressionTranslation as TranslationResult.Translated).text)
        // "up" no tiene entrada propia: no se inventa
        assertEquals(Reason.NO_ENTRY, (result.word as TranslationResult.Unavailable).reason)
        assertEquals("Tom se despierta temprano.", (result.sentenceTranslation as TranslationResult.Translated).text)
    }

    @Test
    fun `word and expression can both be translated`() = runTest {
        val result = lookup.lookup(en, en.passages.first(), tokenOf(en, "wakes"), "es")
        assertEquals("despertar", (result.word as TranslationResult.Translated).text)
        assertEquals("wake", result.wordLemma)
        assertEquals("wake up", result.expression?.entry?.lemma)
    }

    @Test
    fun `examples are translated when available`() = runTest {
        val result = lookup.lookup(en, en.passages.first(), tokenOf(en, "early"), "es")
        val word = result.word as TranslationResult.Translated
        assertEquals("Me levanto temprano.", word.examples.single().translation)
        assertNull(result.expression)
    }

    @Test
    fun `single token contraction is reported as expression`() = runTest {
        val result = lookup.lookup(en, en.passages.first(), tokenOf(en, "Don't"), "es")
        assertEquals(TermKind.CONTRACTION, result.expression?.entry?.kind)
    }

    @Test
    fun `missing target language is unavailable, never invented`() = runTest {
        val result = lookup.lookup(en, en.passages.first(), tokenOf(en, "early"), "fr")
        assertEquals(Reason.NO_ENTRY, (result.word as TranslationResult.Unavailable).reason)
        assertEquals(Reason.NO_ENTRY, (result.sentenceTranslation as TranslationResult.Unavailable).reason)
        // pero la expresión sí tiene francés
        val other = lookup.lookup(en, en.passages.first(), tokenOf(en, "up"), "fr")
        assertEquals("se réveiller", (other.expressionTranslation as TranslationResult.Translated).text)
    }

    @Test
    fun `same source and target language is reported`() = runTest {
        val result = lookup.lookup(en, en.passages.first(), tokenOf(en, "early"), "en-US")
        assertEquals(Reason.SAME_LANGUAGE, (result.word as TranslationResult.Unavailable).reason)
    }

    @Test
    fun `french expression spanning elision is detected from any of its tokens`() = runTest {
        val passage = fr.passages.first()
        for (text in listOf("Qu'", "est-ce", "que")) {
            val result = lookup.lookup(fr, passage, tokenOf(fr, text), "es")
            assertEquals("Qu'est-ce que", result.expression?.surface)
            assertEquals("qué", (result.expressionTranslation as TranslationResult.Translated).text)
        }
    }

    @Test
    fun `typographic apostrophe in glossary matches ascii apostrophe in text`() = runTest {
        val result = lookup.lookup(fr, fr.passages.first(), tokenOf(fr, "est"), "es")
        assertEquals("C'est", result.expression?.surface)
        assertEquals(Reason.NO_ENTRY, (result.sentenceTranslation as TranslationResult.Unavailable).reason)
    }

    @Test
    fun `fallback engine returns first translated result`() = runTest {
        val failing = object : TranslationEngine {
            override val id = "boom"
            override suspend fun translate(request: TranslationRequest): TranslationResult = error("offline")
        }
        val chain = FallbackTranslationEngine(listOf(failing, engine))
        val passage = en.passages.first()
        val result = WordLookupService(chain).lookup(en, passage, tokenOf(en, "early"), "es")
        assertTrue(result.word is TranslationResult.Translated)
    }
}
