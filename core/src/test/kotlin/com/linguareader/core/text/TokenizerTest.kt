package com.linguareader.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenizerTest {

    private fun words(text: String, lang: String) =
        Tokenizer.tokenize(text, lang).map { text.substring(it.start, it.end) }

    @Test
    fun `english punctuation is not tokenized and contractions stay whole`() {
        assertEquals(
            listOf("Perfect", "he", "says", "It's", "a", "good", "day"),
            words("\"Perfect!\" he says. \"It's a good day.\"", "en"),
        )
        assertEquals(listOf("I", "can't", "see", "it"), words("I can't see it…", "en-GB"))
    }

    @Test
    fun `typographic apostrophe is treated like ascii apostrophe`() {
        assertEquals(listOf("Don’t", "worry"), words("Don’t worry!", "en"))
    }

    @Test
    fun `french elision splits article but keeps aujourd'hui`() {
        assertEquals(listOf("l'", "école", "aujourd'hui"), words("l'école, aujourd'hui.", "fr"))
        assertEquals(listOf("Qu'", "est-ce", "que", "je", "vous", "sers"), words("« Qu'est-ce que je vous sers ? »", "fr-FR"))
        assertEquals(listOf("C’", "est", "à", "toi"), words("C’est à toi ?", "fr"))
    }

    @Test
    fun `english does not split elisions`() {
        assertEquals(listOf("l'école"), words("l'école", "en"))
    }

    @Test
    fun `hyphenated words and diacritics are single tokens`() {
        assertEquals(listOf("grand-mère", "très", "âgée"), words("grand-mère, très âgée", "fr"))
        assertEquals(listOf("demande-t-elle"), words("demande-t-elle ?", "fr"))
    }

    @Test
    fun `leading and trailing hyphens and apostrophes are punctuation`() {
        assertEquals(listOf("well", "known"), words("-well- 'known'", "en"))
    }

    @Test
    fun `decomposed unicode is normalized to NFC so ranges stay stable`() {
        val decomposed = "café crème" // é y è descompuestos
        val normalized = Tokenizer.normalize(decomposed)
        assertEquals("café crème", normalized)
        assertEquals(listOf("café", "crème"), words(normalized, "fr"))
    }

    @Test
    fun `combining marks inside words are kept even without normalization`() {
        assertEquals(listOf("café"), words("café!", "fr"))
    }

    @Test
    fun `numbers are tokens and emoji are not`() {
        assertEquals(listOf("Tom", "is", "8"), words("Tom is 8 😀.", "en"))
    }

    @Test
    fun `normalize collapses ascii whitespace but keeps no-break spaces`() {
        assertEquals("a b !", Tokenizer.normalize("  a \n\t b ! "))
    }

    @Test
    fun `match key lowercases and unifies apostrophes`() {
        assertEquals("qu'est-ce que", Tokenizer.matchKey("Qu’est-ce  que"))
    }
}
