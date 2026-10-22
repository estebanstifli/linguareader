package com.linguareader.data.content

import com.linguareader.core.content.BookManifestParser
import com.linguareader.core.content.MediaIntegrity
import com.linguareader.core.content.MediaResult
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.model.Book
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.MediaRef
import com.linguareader.core.model.Passage
import com.linguareader.core.platform.epochMillis
import com.linguareader.core.platform.ioDispatcher
import com.linguareader.importer.ImportedBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** Repositorio con resumen barato por libro (sin abrir el manifiesto): lo usa el catálogo compuesto. */
interface SummaryBookRepository : BookRepository {
    suspend fun summary(bookId: String): BookSummary?
}

/**
 * Libros que el usuario importa (EPUB, TXT, FB2...) o instala desde una biblioteca. Cada uno es una carpeta en
 * `<root>/<id>/` con el mismo `manifest.json` que la biblioteca empaquetada, sus imágenes en `media/` y un
 * `summary.json` para pintar el catálogo sin parsear libros enteros. Código común (Okio): Android e iOS.
 */
class ImportedBookRepository(
    private val fileSystem: FileSystem,
    private val root: Path,
    private val log: (String) -> Unit = {},
) : SummaryBookRepository {

    private val mutex = Mutex()
    private val summaries = MutableStateFlow<Map<String, StoredSummary>?>(null)

    /** Último libro abierto: el lector pide pasajes uno a uno y no conviene re-parsear el manifiesto. */
    private var lastBook: Book? = null

    fun owns(bookId: String): Boolean = bookId.startsWith(ID_PREFIX)

    /** Fichero auxiliar dentro de la carpeta del libro (se borra con él y se descarta al reimportarlo). */
    fun bookFile(bookId: String, name: String): Path {
        require(owns(bookId) && '/' !in bookId && '/' !in name)
        return root / bookId / name
    }

    /** Pretraducción cancelada por el usuario: no se reanuda sola, se continúa desde la tarjeta. */
    data class TranslationPause(val target: String, val done: Int, val total: Int)

    /** Estado de traducción automática de cada libro importado. */
    data class TranslationStatus(
        /** Idioma al que se está pretraduciendo el libro entero (se reanuda al abrir la app). */
        val pending: String?,
        /** Idiomas a los que el libro ya está traducido entero (automáticamente). */
        val done: Set<String>,
        val paused: TranslationPause? = null,
    )

    val translationStatus: Flow<Map<String, TranslationStatus>> =
        summaries.onStart { loaded() }.filterNotNull().map { all ->
            all.mapValues { (_, s) ->
                TranslationStatus(
                    pending = s.pendingTranslation,
                    done = s.machineTranslations.toSet(),
                    paused = s.pausedTranslation?.let { TranslationPause(it, s.pausedDone, s.pausedTotal) },
                )
            }
        }

    /** Fichero original de cada documento que lo conserva referenciado (solo formatos con visor propio). */
    val originals: Flow<Map<String, OriginalDocument>> =
        summaries.onStart { loaded() }.filterNotNull().map { all ->
            all.values.mapNotNull { s -> s.originalUri?.let { s.id to OriginalDocument(it, s.format, s.originalPages) } }.toMap()
        }

    suspend fun original(bookId: String): OriginalDocument? =
        loaded()[bookId]?.let { s -> s.originalUri?.let { OriginalDocument(it, s.format, s.originalPages) } }

    /** El usuario volvió a localizar el original (se movió o caducó el permiso). */
    suspend fun updateOriginal(bookId: String, uri: String) = updateSummary(bookId) { it.copy(originalUri = uri) }

    /** Marca (o desmarca con null) la pretraducción pendiente de un libro. */
    suspend fun setPendingTranslation(bookId: String, language: String?) =
        updateSummary(bookId) { it.copy(pendingTranslation = language, pausedTranslation = if (language != null) null else it.pausedTranslation) }

    /** El usuario canceló: se conserva hasta dónde llegó para poder continuar más tarde. */
    suspend fun pauseTranslation(bookId: String, language: String, done: Int, total: Int) = updateSummary(bookId) {
        it.copy(pendingTranslation = null, pausedTranslation = language, pausedDone = done, pausedTotal = total)
    }

    /** El libro quedó traducido entero a [language]. */
    suspend fun markTranslated(bookId: String, language: String) =
        updateSummary(bookId) {
            it.copy(
                pendingTranslation = null,
                pausedTranslation = null,
                machineTranslations = (it.machineTranslations + language).distinct(),
            )
        }

    private suspend fun updateSummary(bookId: String, transform: (StoredSummary) -> StoredSummary) = withContext(ioDispatcher) {
        loaded()
        mutex.withLock {
            val current = summaries.value?.get(bookId) ?: return@withLock
            val next = transform(current)
            fileSystem.write(root / bookId / SUMMARY) { writeUtf8(JSON.encodeToString(StoredSummary.serializer(), next)) }
            summaries.value = summaries.value.orEmpty() + (bookId to next)
        }
    }

    override suspend fun summary(bookId: String): BookSummary? = loaded()[bookId]?.toSummary()

    suspend fun contains(bookId: String): Boolean = loaded().containsKey(bookId)

    override fun observeCatalog(filter: CatalogFilter): Flow<List<BookSummary>> =
        summaries.onStart { loaded() }.filterNotNull().map { all ->
            all.values.sortedByDescending { it.importedAt }.map { it.toSummary() }.filter(filter::matches)
        }

    override suspend fun loadBook(bookId: String): Book {
        lastBook?.takeIf { it.id == bookId }?.let { return it }
        return withContext(ioDispatcher) {
            val manifest = root / bookId / MANIFEST
            if (fileSystem.metadataOrNull(manifest)?.isRegularFile != true) throw NoSuchElementException("Libro no encontrado: $bookId")
            BookManifestParser.parse(fileSystem.read(manifest) { readUtf8() })
        }.also { lastBook = it }
    }

    override suspend fun loadPassage(bookId: String, passageId: String): Passage =
        loadBook(bookId).passages.firstOrNull { it.id == passageId }
            ?: throw NoSuchElementException("Pasaje no encontrado: $bookId/$passageId")

    override suspend fun loadMedia(bookId: String, media: MediaRef): MediaResult = withContext(ioDispatcher) {
        val file = root / bookId / media.path
        val bytes = if (fileSystem.metadataOrNull(file)?.isRegularFile == true) fileSystem.read(file) { readByteArray() } else null
        MediaIntegrity.verify(media, bytes)
    }

    /** Escribe el libro en una carpeta temporal y la renombra: nunca queda un libro a medias. */
    suspend fun save(book: ImportedBook, format: String, original: OriginalDocument? = null): BookSummary = withContext(ioDispatcher) {
        val parsed = BookManifestParser.toDomain(book.manifest)
        val id = parsed.id
        val stored = StoredSummary.from(parsed, format, epochMillis())
            .copy(originalUri = original?.uri, originalPages = original?.pageCount)
        mutex.withLock {
            fileSystem.createDirectories(root)
            val staging = root / ".$id.tmp"
            fileSystem.deleteRecursively(staging, mustExist = false)
            fileSystem.createDirectories(staging)
            fileSystem.write(staging / MANIFEST) { writeUtf8(book.manifestJson()) }
            book.media.forEach { (path, bytes) ->
                val file = staging / path
                file.parent?.let { fileSystem.createDirectories(it) }
                fileSystem.write(file) { write(bytes) }
            }
            fileSystem.write(staging / SUMMARY) { writeUtf8(JSON.encodeToString(StoredSummary.serializer(), stored)) }
            val target = root / id
            fileSystem.deleteRecursively(target, mustExist = false)
            fileSystem.atomicMove(staging, target)
            if (lastBook?.id == id) lastBook = null
            summaries.value = (summaries.value ?: readAll()) + (id to stored)
        }
        stored.toSummary()
    }

    suspend fun delete(bookId: String) = withContext(ioDispatcher) {
        require(owns(bookId)) { "Solo se pueden borrar libros importados" }
        mutex.withLock {
            fileSystem.deleteRecursively(root / bookId, mustExist = false)
            if (lastBook?.id == bookId) lastBook = null
            summaries.value = (summaries.value ?: readAll()) - bookId
        }
    }

    private suspend fun loaded(): Map<String, StoredSummary> =
        summaries.value ?: mutex.withLock {
            summaries.value ?: withContext(ioDispatcher) { readAll() }.also { summaries.value = it }
        }

    private fun readAll(): Map<String, StoredSummary> =
        fileSystem.listOrNull(root).orEmpty()
            .filter { fileSystem.metadataOrNull(it)?.isDirectory == true && !it.name.startsWith(".") }
            .mapNotNull { dir ->
                runCatching { JSON.decodeFromString(StoredSummary.serializer(), fileSystem.read(dir / SUMMARY) { readUtf8() }) }
                    .onFailure { log("Libro importado '${dir.name}' ilegible: ${it.message}") }
                    .getOrNull()
                    ?.takeIf { it.id == dir.name }
            }.associateBy { it.id }

    @Serializable
    private data class StoredSummary(
        val id: String,
        val title: String,
        val authors: String? = null,
        val learningLanguage: String,
        val cefrLevel: String,
        val coverPath: String? = null,
        val coverSha256: String? = null,
        val wordCount: Int,
        val passageCount: Int,
        val format: String,
        val importedAt: Long,
        val pendingTranslation: String? = null,
        val machineTranslations: List<String> = emptyList(),
        val pausedTranslation: String? = null,
        val pausedDone: Int = 0,
        val pausedTotal: Int = 0,
        /** URI (`content://` con permiso persistente) o `file://` del fichero importado; no se copia. */
        val originalUri: String? = null,
        val originalPages: Int? = null,
    ) {
        fun toSummary(): BookSummary {
            val level = CefrLevel.parse(cefrLevel) ?: CefrLevel.B1
            return BookSummary(
                id = id,
                title = title,
                description = authors,
                learningLanguage = learningLanguage,
                cefrLevel = level,
                genre = null,
                cover = coverPath?.let { MediaRef(it, alt = null, credit = null, sha256 = coverSha256) },
                wordCount = wordCount,
                estimatedMinutes = Book.estimateMinutes(wordCount, level),
                passageCount = passageCount,
                translationLanguages = emptySet(),
                authors = listOfNotNull(authors?.takeIf { it.isNotBlank() }),
            )
        }

        companion object {
            fun from(book: Book, format: String, importedAt: Long): StoredSummary {
                val summary = book.summary()
                return StoredSummary(
                    id = summary.id,
                    title = summary.title,
                    authors = book.authors.joinToString(", ").ifEmpty { null },
                    learningLanguage = summary.learningLanguage,
                    cefrLevel = summary.cefrLevel.name,
                    coverPath = summary.cover?.path,
                    coverSha256 = summary.cover?.sha256,
                    wordCount = summary.wordCount,
                    passageCount = summary.passageCount,
                    format = format,
                    importedAt = importedAt,
                )
            }
        }
    }

    companion object {
        /** Los ids importados llevan este prefijo; los empaquetados no deben usarlo. */
        const val ID_PREFIX = "u-"
        const val DIR = "imported"
        private const val MANIFEST = "manifest.json"
        private const val SUMMARY = "summary.json"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
