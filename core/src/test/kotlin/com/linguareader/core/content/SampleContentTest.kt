package com.linguareader.core.content

import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.TermKind
import com.linguareader.core.text.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Valida los libros de prueba del formato: los empaquetados en app/src/main/assets/books y los de
 * content/test-books (fuera del APK; los usan los tests como ejemplo completo con imágenes, traducciones y glosario).
 */
class SampleContentTest {

    private val roots = listOf(File("../app/src/main/assets/books"), File("../content/test-books"))
    private val books = roots.flatMap { root ->
        BookManifestParser.parseIndex(File(root, "index.json").readText()).books.map { id ->
            Triple(root, id, BookManifestParser.parse(File(root, "$id/manifest.json").readText()))
        }
    }.map { (root, id, book) -> "${root.path}/$id" to book }

    @Test
    fun `catalog has at least two books in different languages and levels`() {
        assertTrue(books.size >= 2)
        assertTrue(books.map { it.second.learningLanguage }.toSet().size >= 2)
        assertTrue(books.map { it.second.cefrLevel }.toSet().containsAll(listOf(CefrLevel.A1, CefrLevel.A2)))
    }

    @Test
    fun `folder name matches manifest id`() {
        books.forEach { (folder, book) -> assertEquals(File(folder).name, book.id) }
    }

    @Test
    fun `every media file exists and matches its sha256`() {
        // Las ilustraciones son opcionales (el libro de prueba de normalización no tiene), pero el catálogo las usa.
        assertTrue("Ningún libro tiene imágenes", books.any { (_, book) -> book.cover != null || book.passages.any { it.image != null } })
        books.forEach { (folder, book) ->
            val media = listOfNotNull(book.cover) + book.passages.mapNotNull { it.image }
            media.forEach { ref ->
                val file = File(folder, ref.path)
                assertTrue("Falta ${file.path}", file.exists())
                val result = MediaIntegrity.verify(ref, file.readBytes())
                assertTrue("Hash incorrecto en ${ref.path}: $result", result is MediaResult.Ok)
                assertTrue("Falta alt en ${ref.path}", !ref.alt.isNullOrBlank())
            }
        }
    }

    @Test
    fun `some passages have no image so illustrations stay optional`() {
        books.forEach { (_, book) -> assertTrue(book.passages.any { it.image == null }) }
    }

    @Test
    fun `every sentence has a spanish translation for bilingual mode`() {
        books.forEach { (_, book) -> assertTrue(book.id, "es" in book.fullyTranslatedLanguages) }
    }

    @Test
    fun `every expression in the glossary occurs in the text in at least one form`() {
        books.forEach { (_, book) ->
            val text = Tokenizer.matchKey(book.passages.joinToString(" ") { it.text })
            book.glossary.filter { it.kind != TermKind.WORD }.forEach { entry ->
                assertTrue("'${entry.lemma}' no aparece en ${book.id}", entry.forms.any(text::contains))
            }
        }
    }
}
