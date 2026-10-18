package com.linguareader.importer

import com.linguareader.core.content.BookManifestParser
import com.linguareader.core.content.Hashing
import com.linguareader.core.model.CefrLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportedBookBuilderTest {

    @get:Rule val folder = TemporaryFolder()

    private fun options(language: String = "en") = ImportOptions(
        bookId = "u-0123456789ab",
        title = "Imported",
        authors = listOf("Someone"),
        language = language,
        level = CefrLevel.B1,
        sourceName = "book.epub",
    )

    @Test
    fun `el manifiesto generado lo acepta el parser del lector, con tokens coherentes`() {
        val document = ExtractorRegistry.default().extract(DocumentSource(Fixtures.epub(folder), "book.epub"))
        val imported = ImportedBookBuilder().build(document, options())

        val book = BookManifestParser.parse(imported.manifestJson())
        assertEquals("u-0123456789ab", book.id)
        assertEquals("user-import", book.license.id)
        assertEquals("book.epub", book.sourceAttribution)
        assertEquals(listOf("One: The Storm", "Two: Morning"), book.chapters.map { it.title })
        // La imagen intermedia ilustra el pasaje siguiente, que empieza en "He lit the lamp".
        val illustrated = book.passages.single { it.image != null }
        assertTrue(illustrated.text.startsWith("He lit the lamp"))
        assertEquals("A lighthouse", illustrated.image!!.alt)
        assertTrue(illustrated.image!!.path in imported.media)
        assertNotNull(book.cover)
        assertEquals(Hashing.sha256Hex(imported.media.getValue(book.cover!!.path)), book.cover!!.sha256)

        book.passages.forEach { passage ->
            passage.tokens.forEach { token -> assertEquals(token.text, passage.text.substring(token.rangeStart, token.rangeEnd)) }
            passage.sentences.forEach { s -> assertEquals(s.text, passage.text.substring(s.rangeStart, s.rangeEnd)) }
        }
        // "Mr. Gray" no parte la frase.
        assertTrue(book.passages.first().sentences.any { it.text == "Mr. Gray closed the door." })
    }

    @Test
    fun `reparte párrafos largos en pasajes acotados`() {
        val sentence = "The quick brown fox jumps over the lazy dog again and again."
        val paragraphs = (1..30).map { ExtractedBlock.Paragraph(List(4) { sentence }.joinToString(" ")) }
        val document = ExtractedDocument("txt", "Fox", chapters = listOf(ExtractedChapter(null, paragraphs)))
        val book = BookManifestParser.toDomain(ImportedBookBuilder(targetWords = 100, maxWords = 150).build(document, options()).manifest)

        assertEquals("Capítulo 1", book.chapters.single().title)
        // 48 palabras por párrafo: se agrupan de 3 en 3 (144 ≥ 100 objetivo, ≤ 150 máximo).
        assertEquals(10, book.passages.size)
        book.passages.forEach { assertTrue("Pasaje con ${it.tokens.size} palabras", it.tokens.size <= 150) }
        assertEquals(30 * 4, book.passages.sumOf { it.sentences.size })
        assertNull(book.passages.first().image)
    }
}
