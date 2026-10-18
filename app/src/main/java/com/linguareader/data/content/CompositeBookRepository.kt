package com.linguareader.data.content

import com.linguareader.core.content.MediaResult
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.Book
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.MediaRef
import com.linguareader.core.model.Passage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Biblioteca empaquetada + libros importados por el usuario, vistos como un único catálogo. */
@Singleton
class CompositeBookRepository @Inject constructor(
    private val bundled: AssetBookRepository,
    private val imported: ImportedBookRepository,
) : BookRepository {

    /** Primero lo importado (más reciente arriba) y después la biblioteca incluida. */
    override fun observeCatalog(filter: CatalogFilter): Flow<List<BookSummary>> =
        combine(imported.observeCatalog(filter), bundled.observeCatalog(filter)) { mine, included -> mine + included }

    override suspend fun loadBook(bookId: String): Book = route(bookId).loadBook(bookId)

    override suspend fun loadPassage(bookId: String, passageId: String): Passage = route(bookId).loadPassage(bookId, passageId)

    override suspend fun loadMedia(bookId: String, media: MediaRef): MediaResult = route(bookId).loadMedia(bookId, media)

    private fun route(bookId: String): BookRepository = if (imported.owns(bookId)) imported else bundled
}
