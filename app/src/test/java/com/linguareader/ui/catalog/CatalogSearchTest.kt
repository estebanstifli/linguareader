package com.linguareader.ui.catalog

import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CefrLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSearchTest {

    private fun book(id: String, title: String, author: String? = null, level: CefrLevel = CefrLevel.B1, minutes: Int = 60) =
        BookSummary(
            id = id, title = title, description = null, learningLanguage = "en", cefrLevel = level, genre = null, cover = null,
            wordCount = 1000, estimatedMinutes = minutes, passageCount = 10, translationLanguages = emptySet(),
            authors = listOfNotNull(author),
        )

    private val books = listOf(
        book("1", "Anna Karénina", "León Tolstói", CefrLevel.C1, 900),
        book("2", "El sabueso de los Baskerville", "Arthur Conan Doyle", CefrLevel.B2, 300),
        book("3", "Notas sueltas", null, CefrLevel.A2, 20),
        book("4", "Ábaco", "Ana Ruiz", CefrLevel.A1, 45),
    )
    private val progress = mapOf(
        "1" to CatalogSearch.Progress(0.3f, updatedAt = 100),
        "2" to CatalogSearch.Progress(1f, updatedAt = 200),
    )

    private fun ids(search: CatalogSearch, finished: Set<String> = emptySet()) = search.apply(books, progress, finished).map { it.id }

    @Test
    fun `text matches title or author ignoring accents and case`() {
        assertEquals(listOf("1"), ids(CatalogSearch(text = "karenina")))
        assertEquals(listOf("1"), ids(CatalogSearch(text = "TOLSTOI")))
        assertEquals(listOf("2"), ids(CatalogSearch(text = "conan")))
        assertEquals(books.map { it.id }, ids(CatalogSearch(text = "  ")))
    }

    @Test
    fun `status uses progress and the read shelf`() {
        assertEquals(listOf("1"), ids(CatalogSearch(status = ReadStatus.READING)))
        assertEquals(listOf("2"), ids(CatalogSearch(status = ReadStatus.FINISHED)))
        assertEquals(listOf("3", "4"), ids(CatalogSearch(status = ReadStatus.NOT_STARTED)))
        // Marcado como leído aunque no se haya abierto.
        assertEquals(listOf("2", "3"), ids(CatalogSearch(status = ReadStatus.FINISHED), finished = setOf("3")))
    }

    @Test
    fun `sort orders`() {
        assertEquals(listOf("4", "1", "2", "3"), ids(CatalogSearch(sort = SortOrder.TITLE)))
        assertEquals(listOf("4", "2", "1", "3"), ids(CatalogSearch(sort = SortOrder.AUTHOR)))
        assertEquals(listOf("4", "3", "2", "1"), ids(CatalogSearch(sort = SortOrder.LEVEL)))
        assertEquals(listOf("2", "1", "4", "3"), ids(CatalogSearch(sort = SortOrder.RECENT)))
        assertEquals(listOf("3", "4", "2", "1"), ids(CatalogSearch(sort = SortOrder.SHORTEST)))
        assertEquals(books.map { it.id }, ids(CatalogSearch()))
    }

    @Test
    fun `active only when something is set`() {
        assertFalse(CatalogSearch().isActive)
        assertTrue(CatalogSearch(sort = SortOrder.TITLE).isActive)
        assertTrue(CatalogSearch(text = "x").isActive)
    }
}
