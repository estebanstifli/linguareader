package com.linguareader.data.content

import com.linguareader.core.content.MediaResult
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.Book
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.MediaRef
import com.linguareader.core.model.Passage
import com.linguareader.data.db.BookOverrideEntity
import com.linguareader.data.db.LibraryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Biblioteca empaquetada + libros importados por el usuario, vistos como un único catálogo, con los datos que el
 * usuario haya corregido (título, autor, idioma, nivel) aplicados encima.
 */
class CompositeBookRepository(
    private val bundled: AssetBookRepository,
    private val imported: ImportedBookRepository,
    private val library: LibraryDao,
) : BookRepository {

    /** Primero lo importado (más reciente arriba) y después la biblioteca incluida. */
    override fun observeCatalog(filter: CatalogFilter): Flow<List<BookSummary>> =
        combine(
            imported.observeCatalog(CatalogFilter()),
            bundled.observeCatalog(CatalogFilter()),
            library.observeOverrides().map { list -> list.associateBy { it.bookId } },
        ) { mine, included, overrides ->
            // El filtro se aplica después: un idioma o nivel corregido decide en qué apartado sale el libro.
            (mine + included).map { s -> overrides[s.id]?.let(s::with) ?: s }.filter(filter::matches)
        }

    override suspend fun loadBook(bookId: String): Book {
        val book = route(bookId).loadBook(bookId)
        return library.override(bookId)?.let(book::with) ?: book
    }

    /**
     * Resumen de un libro (con las correcciones aplicadas) sin abrir el libro entero ni esperar al catálogo completo:
     * lo usa la pantalla «Editar».
     */
    suspend fun loadSummary(bookId: String): BookSummary? {
        val summary = if (imported.owns(bookId)) imported.summary(bookId) else bundled.summary(bookId)
        return summary?.let { s -> library.override(bookId)?.let(s::with) ?: s }
    }

    override suspend fun loadPassage(bookId: String, passageId: String): Passage = route(bookId).loadPassage(bookId, passageId)

    override suspend fun loadMedia(bookId: String, media: MediaRef): MediaResult = route(bookId).loadMedia(bookId, media)

    private fun route(bookId: String): BookRepository = if (imported.owns(bookId)) imported else bundled
}

private fun BookSummary.with(o: BookOverrideEntity) = copy(
    title = o.title ?: title,
    authors = o.author?.let { a -> listOfNotNull(a.takeIf { it.isNotBlank() }) } ?: authors,
    learningLanguage = o.language ?: learningLanguage,
    cefrLevel = o.cefrLevel?.let(CefrLevel::parse) ?: cefrLevel,
)

private fun Book.with(o: BookOverrideEntity) = copy(
    title = o.title ?: title,
    authors = o.author?.let { a -> listOfNotNull(a.takeIf { it.isNotBlank() }) } ?: authors,
    learningLanguage = o.language ?: learningLanguage,
    cefrLevel = o.cefrLevel?.let(CefrLevel::parse) ?: cefrLevel,
)
