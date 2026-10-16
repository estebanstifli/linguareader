package com.linguareader.core.content

import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.TermKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BookManifestParserTest {

    private fun manifest(
        level: String = "A1",
        imagePath: String = "images/p1.png",
        version: Int = 1,
        secondPassageId: String = "p2",
    ) = """
        {
          "formatVersion": $version,
          "id": "demo-book",
          "title": "Demo",
          "learningLanguage": "en",
          "cefrLevel": "$level",
          "license": { "id": "LicenseRef-Test" },
          "sourceAttribution": "Test",
          "futureField": { "ignored": true },
          "chapters": [
            { "id": "ch1", "title": "One", "passages": [
              { "id": "p1", "image": { "path": "$imagePath", "alt": "A kite" },
                "sentences": [ { "id": "s1", "text": "Tom wakes up.", "translations": { "es": "Tom se despierta." } } ] },
              { "id": "$secondPassageId", "sentences": [ { "id": "s1", "text": "Hi." } ] }
            ] }
          ],
          "glossary": [
            { "id": "g1", "lemma": "wake up", "kind": "phrasal_verb", "forms": ["Wakes up", "woke up"], "translations": { "es": "despertarse" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a valid manifest`() {
        val book = BookManifestParser.parse(manifest())
        assertEquals("demo-book", book.id)
        assertEquals(CefrLevel.A1, book.cefrLevel)
        assertEquals(2, book.passages.size)
        assertEquals(listOf(0, 1), book.passages.map { it.index })
        assertEquals("A kite", book.passages[0].image?.alt)
        assertEquals(null, book.passages[1].image)
        assertEquals(TermKind.PHRASAL_VERB, book.glossary.single().kind)
        assertEquals(listOf("wakes up", "woke up"), book.glossary.single().forms)
        assertEquals(4, book.wordCount)
        assertTrue(book.fullyTranslatedLanguages.isEmpty())
    }

    @Test
    fun `rejects unknown cefr level`() = assertInvalid(manifest(level = "Z9"), "cefrLevel")

    @Test
    fun `rejects path traversal in media`() = assertInvalid(manifest(imagePath = "../../secret.png"), "Ruta")

    @Test
    fun `rejects absolute media paths`() = assertInvalid(manifest(imagePath = "/sdcard/x.png"), "Ruta")

    @Test
    fun `rejects unsupported format version`() = assertInvalid(manifest(version = 99), "formatVersion")

    @Test
    fun `rejects duplicated passage ids`() = assertInvalid(manifest(secondPassageId = "p1"), "duplicado")

    @Test
    fun `rejects malformed json`() = assertInvalid("{ not json", "inválido")

    private fun assertInvalid(json: String, messagePart: String) {
        try {
            BookManifestParser.parse(json)
            fail("Se esperaba ContentFormatException")
        } catch (e: ContentFormatException) {
            assertTrue("'${e.message}' debería contener '$messagePart'", e.message!!.contains(messagePart))
        }
    }
}
