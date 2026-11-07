package com.linguareader.data.catalog

import com.linguareader.core.model.CefrLevel
import com.linguareader.core.platform.epochMillis
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.content.LocalBookImporter
import com.linguareader.engine.supertonic.HttpTransport
import com.linguareader.importer.ExtractedImage
import com.linguareader.importer.extract.ImageTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * Añade un libro del catálogo online a la biblioteca (solo tras confirmarlo el usuario): descarga el EPUB/TXT a la
 * caché, lo importa con el importador compartido y lo guarda con un id estable por proveedor y libro, que conserva el
 * progreso y evita duplicados. Código común (Android e iOS).
 */
class OnlineBookImporter(
    private val importer: LocalBookImporter,
    private val imported: ImportedBookRepository,
    private val fileSystem: FileSystem,
    private val cacheDir: Path,
    private val transport: HttpTransport,
) {
    private val mutex = Mutex()
    fun bookId(provider: OnlineCatalog, book: OnlineBook) = ImportedBookRepository.ID_PREFIX + stableId(provider, book)
    suspend fun exists(provider: OnlineCatalog, book: OnlineBook) = imported.contains(bookId(provider, book))

    suspend fun add(provider: OnlineCatalog, book: OnlineBook, level: CefrLevel): String = mutex.withLock {
        val id = bookId(provider, book)
        if (imported.contains(id)) return@withLock id
        val format = requireNotNull(book.download) { "No supported book format" }
        fileSystem.createDirectories(cacheDir)
        val file = cacheDir / "catalog-${epochMillis()}.${format.kind}"
        try {
            fileSystem.sink(file).buffer().use { CatalogHttp.transfer(transport, format.url, it, LocalBookImporter.MAX_FILE_BYTES) }
            var draft = importer.readFile(file, "book.${format.kind}").copy(sourceName = book.source_url ?: format.url)
            val cover = reviewedCatalogCover(book) { url, limit -> provider.fetch(url, limit) }
            if (cover != null) draft = draft.copy(document = draft.document.copy(cover = cover))
            importer.commit(
                draft, book.title, book.author_names.orEmpty(), book.languageTags.firstOrNull() ?: "en", level,
                stableId(provider, book), authors = book.author_names.orEmpty().split('·').map(String::trim).filter(String::isNotBlank),
            )
        } finally {
            fileSystem.delete(file, mustExist = false)
        }
    }

    private fun stableId(provider: OnlineCatalog, book: OnlineBook) = "catalog-${provider.id}-${book.id}"
}

/** Keep reviewed artwork offline; cover failures do not prevent importing the book. */
internal suspend fun reviewedCatalogCover(
    book: OnlineBook,
    fetch: suspend (String, Long) -> ByteArray,
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
