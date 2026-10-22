package com.linguareader.data.content

import android.content.Context
import android.util.Log
import com.linguareader.core.content.BookManifestParser
import com.linguareader.core.content.MediaIntegrity
import com.linguareader.core.content.MediaResult
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.Book
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.MediaRef
import com.linguareader.core.model.Passage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Biblioteca empaquetada en `assets/books/`. Un manifiesto inválido se descarta (con log)
 * sin impedir que el resto del catálogo funcione. Todo es local: no requiere red.
 */
class AssetBookRepository(
    private val context: Context,
) : SummaryBookRepository {

    private val mutex = Mutex()
    private var cache: Map<String, Book>? = null

    private suspend fun books(): Map<String, Book> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) { loadAll() }.also { cache = it }
    }

    private fun loadAll(): Map<String, Book> {
        val index = BookManifestParser.parseIndex(read("$ROOT/index.json"))
        return index.books.mapNotNull { folder ->
            runCatching { BookManifestParser.parse(read("$ROOT/$folder/manifest.json")) }
                .onFailure { Log.w(TAG, "Libro '$folder' descartado: ${it.message}") }
                .getOrNull()
                ?.takeIf { book ->
                    (book.id == folder).also { ok -> if (!ok) Log.w(TAG, "Carpeta '$folder' ≠ id '${book.id}'") }
                }
        }.associateBy { it.id }
    }

    private fun read(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    override fun observeCatalog(filter: CatalogFilter): Flow<List<BookSummary>> = flow {
        emit(
            books().values.map { it.summary() }
                .filter(filter::matches)
                .sortedWith(compareBy<BookSummary> { it.cefrLevel }.thenBy { it.title }),
        )
    }

    override suspend fun summary(bookId: String): BookSummary? = books()[bookId]?.summary()

    override suspend fun loadBook(bookId: String): Book =
        books()[bookId] ?: throw NoSuchElementException("Libro no encontrado: $bookId")

    override suspend fun loadPassage(bookId: String, passageId: String): Passage =
        loadBook(bookId).passages.firstOrNull { it.id == passageId }
            ?: throw NoSuchElementException("Pasaje no encontrado: $bookId/$passageId")

    override suspend fun loadMedia(bookId: String, media: MediaRef): MediaResult = withContext(Dispatchers.IO) {
        val bytes = try {
            context.assets.open("$ROOT/$bookId/${media.path}").use { it.readBytes() }
        } catch (_: IOException) {
            null
        }
        MediaIntegrity.verify(media, bytes).also {
            if (it is MediaResult.IntegrityError) Log.w(TAG, "Integridad fallida en $bookId/${media.path}")
        }
    }

    private companion object {
        const val ROOT = "books"
        const val TAG = "AssetBookRepository"
    }
}
