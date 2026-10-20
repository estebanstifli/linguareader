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
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.MediaRef
import com.linguareader.core.model.Passage
import com.linguareader.importer.ImportedBook
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Libros que el usuario importa (EPUB, TXT, FB2...). Cada uno es una carpeta en
 * `filesDir/imported/<id>/` con el mismo `manifest.json` que la biblioteca empaquetada, sus
 * imágenes en `media/` y un `summary.json` para pintar el catálogo sin parsear libros enteros.
 */
@Singleton
class ImportedBookRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : BookRepository {

    private val root: File get() = File(context.filesDir, DIR)
    private val mutex = Mutex()
    private val summaries = MutableStateFlow<Map<String, StoredSummary>?>(null)

    /** Último libro abierto: el lector pide pasajes uno a uno y no conviene re-parsear el manifiesto. */
    @Volatile private var lastBook: Book? = null

    fun owns(bookId: String): Boolean = bookId.startsWith(ID_PREFIX)

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

    private suspend fun updateSummary(bookId: String, transform: (StoredSummary) -> StoredSummary) = withContext(Dispatchers.IO) {
        loaded()
        mutex.withLock {
            val current = summaries.value?.get(bookId) ?: return@withLock
            val next = transform(current)
            File(File(root, bookId), SUMMARY).writeText(JSON.encodeToString(StoredSummary.serializer(), next))
            summaries.value = summaries.value.orEmpty() + (bookId to next)
        }
    }

    suspend fun summary(bookId: String): BookSummary? = loaded()[bookId]?.toSummary()

    suspend fun contains(bookId: String): Boolean = loaded().containsKey(bookId)

    override fun observeCatalog(filter: CatalogFilter): Flow<List<BookSummary>> =
        summaries.onStart { loaded() }.filterNotNull().map { all ->
            all.values.sortedByDescending { it.importedAt }.map { it.toSummary() }.filter(filter::matches)
        }

    override suspend fun loadBook(bookId: String): Book {
        lastBook?.takeIf { it.id == bookId }?.let { return it }
        return withContext(Dispatchers.IO) {
            val manifest = File(File(root, bookId), MANIFEST)
            if (!manifest.isFile) throw NoSuchElementException("Libro no encontrado: $bookId")
            BookManifestParser.parse(manifest.readText())
        }.also { lastBook = it }
    }

    override suspend fun loadPassage(bookId: String, passageId: String): Passage =
        loadBook(bookId).passages.firstOrNull { it.id == passageId }
            ?: throw NoSuchElementException("Pasaje no encontrado: $bookId/$passageId")

    override suspend fun loadMedia(bookId: String, media: MediaRef): MediaResult = withContext(Dispatchers.IO) {
        val file = File(File(root, bookId), media.path)
        MediaIntegrity.verify(media, file.takeIf { it.isFile }?.readBytes())
    }

    /** Escribe el libro en una carpeta temporal y la renombra: nunca queda un libro a medias. */
    suspend fun save(book: ImportedBook, format: String): BookSummary = withContext(Dispatchers.IO) {
        val parsed = BookManifestParser.toDomain(book.manifest)
        val id = parsed.id
        val stored = StoredSummary.from(parsed, format, System.currentTimeMillis())
        mutex.withLock {
            root.mkdirs()
            val staging = File(root, ".$id.tmp").apply { deleteRecursively(); mkdirs() }
            File(staging, MANIFEST).writeText(book.manifestJson())
            book.media.forEach { (path, bytes) -> File(staging, path).apply { parentFile?.mkdirs() }.writeBytes(bytes) }
            File(staging, SUMMARY).writeText(JSON.encodeToString(StoredSummary.serializer(), stored))
            val target = File(root, id)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "No se pudo guardar el libro importado" }
            if (lastBook?.id == id) lastBook = null
            summaries.value = (summaries.value ?: readAll()) + (id to stored)
        }
        stored.toSummary()
    }

    suspend fun delete(bookId: String) = withContext(Dispatchers.IO) {
        require(owns(bookId)) { "Solo se pueden borrar libros importados" }
        mutex.withLock {
            File(root, bookId).deleteRecursively()
            if (lastBook?.id == bookId) lastBook = null
            summaries.value = (summaries.value ?: readAll()) - bookId
        }
    }

    private suspend fun loaded(): Map<String, StoredSummary> =
        summaries.value ?: mutex.withLock {
            summaries.value ?: withContext(Dispatchers.IO) { readAll() }.also { summaries.value = it }
        }

    private fun readAll(): Map<String, StoredSummary> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { dir ->
            runCatching { JSON.decodeFromString(StoredSummary.serializer(), File(dir, SUMMARY).readText()) }
                .onFailure { Log.w(TAG, "Libro importado '${dir.name}' ilegible: ${it.message}") }
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
        private const val DIR = "imported"
        private const val MANIFEST = "manifest.json"
        private const val SUMMARY = "summary.json"
        private const val TAG = "ImportedBooks"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
