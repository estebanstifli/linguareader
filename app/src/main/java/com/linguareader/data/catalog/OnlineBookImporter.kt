package com.linguareader.data.catalog

import android.content.Context
import com.linguareader.core.model.CefrLevel
import com.linguareader.data.content.BookImportService
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.importer.ExtractedImage
import com.linguareader.importer.extract.ImageTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Only invoked after confirmation. A stable provider/book id preserves progress and prevents duplicates. */
class OnlineBookImporter(
    private val context: Context,
    private val service: BookImportService,
    private val imported: ImportedBookRepository,
) {
    private val mutex = Mutex()
    fun bookId(provider: OnlineCatalog, book: OnlineBook) = ImportedBookRepository.ID_PREFIX + stableId(provider, book)
    suspend fun exists(provider: OnlineCatalog, book: OnlineBook) = imported.contains(bookId(provider, book))

    suspend fun add(provider: OnlineCatalog, book: OnlineBook, level: CefrLevel): String = mutex.withLock {
        val id = bookId(provider, book)
        if (imported.contains(id)) return@withLock id
        val format = requireNotNull(book.download) { "No supported book format" }
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("catalog-", ".${format.kind}", context.cacheDir)
            try {
                file.outputStream().use { CatalogHttp.transfer(format.url, it, BookImportService.MAX_FILE_BYTES) }
                var draft = service.readFile(file).copy(sourceName = book.source_url ?: format.url)
                val cover = reviewedCatalogCover(book)
                if (cover != null) draft = draft.copy(document = draft.document.copy(cover = cover))
                service.commit(draft, book.title, book.author_names.orEmpty(), book.languageTags.firstOrNull() ?: "en", level,
                    stableId(provider, book), authors = book.author_names.orEmpty().split('·').map(String::trim).filter(String::isNotBlank))
            } finally {
                file.delete()
            }
        }
    }

    private fun stableId(provider: OnlineCatalog, book: OnlineBook) = "catalog-${provider.id}-${book.id}"
}

/** Keep reviewed artwork offline; cover failures do not prevent importing the book. */
internal suspend fun reviewedCatalogCover(
    book: OnlineBook,
    fetch: suspend (String, Long) -> ByteArray = { url, limit -> CatalogHttp.bytes(url, limit) },
): ExtractedImage? {
    if (book.r2_key == null || book.cover_url == null) return null
    return try {
        val bytes = fetch(book.cover_url, 5L * 1024 * 1024)
        if (ImageTypes.extension(bytes) != null) ExtractedImage(bytes) else null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
