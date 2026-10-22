package com.linguareader.shared

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
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Libros guardados en disco como `<root>/<id>/manifest.json` + media (el mismo formato que los importados en
 * Android, `docs/CONTENT_FORMAT.md`). Un manifiesto inválido se descarta sin impedir que el resto funcione.
 */
class DirectoryBookRepository(private val fs: FileSystem, private val root: Path) : BookRepository {

    private val mutex = Mutex()
    private var cache: Map<String, Book>? = null
    private val catalog = MutableStateFlow<List<BookSummary>?>(null)

    private suspend fun books(): Map<String, Book> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) { loadAll() }.also { loaded ->
            cache = loaded
            catalog.value = loaded.values.map { it.summary() }
        }
    }

    private fun loadAll(): Map<String, Book> {
        val dirs = fs.listOrNull(root).orEmpty().filter { fs.metadataOrNull(it)?.isDirectory == true && !it.name.startsWith(".") }
        return dirs.mapNotNull { dir ->
            val manifest = dir / MANIFEST
            if (!fs.exists(manifest)) return@mapNotNull null
            runCatching { BookManifestParser.parse(fs.read(manifest) { readUtf8() }) }
                .onFailure { println("DirectoryBookRepository: libro '${dir.name}' descartado: ${it.message}") }
                .getOrNull()
        }.associateBy { it.id }
    }

    /** Vuelve a leer el directorio (tras importar o borrar). */
    suspend fun refresh() {
        mutex.withLock { cache = null }
        books()
    }

    fun isEmpty(): Boolean = fs.listOrNull(root).orEmpty().none { fs.exists(it / MANIFEST) }

    fun bookDir(bookId: String): Path = root / bookId

    override fun observeCatalog(filter: CatalogFilter): Flow<List<BookSummary>> =
        catalog.onStart { books() }
            .map { list ->
                list.orEmpty().filter(filter::matches)
                    .sortedWith(compareBy<BookSummary> { it.cefrLevel }.thenBy { it.title })
            }

    override suspend fun loadBook(bookId: String): Book =
        books()[bookId] ?: throw NoSuchElementException("Libro no encontrado: $bookId")

    override suspend fun loadPassage(bookId: String, passageId: String): Passage =
        loadBook(bookId).passages.firstOrNull { it.id == passageId }
            ?: throw NoSuchElementException("Pasaje no encontrado: $bookId/$passageId")

    override suspend fun loadMedia(bookId: String, media: MediaRef): MediaResult = withContext(Dispatchers.IO) {
        val file = root / bookId / media.path
        val bytes = if (fs.exists(file)) runCatching { fs.read(file) { readByteArray() } }.getOrNull() else null
        MediaIntegrity.verify(media, bytes)
    }

    private companion object {
        const val MANIFEST = "manifest.json"
    }
}
