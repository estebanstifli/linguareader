package com.linguareader.data.content

import com.linguareader.core.content.Hashing
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.platform.Sha256
import com.linguareader.core.platform.ioDispatcher
import com.linguareader.core.platform.toHexLower
import com.linguareader.data.narration.NarrationLibrary
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedDocument
import com.linguareader.importer.ExtractorRegistry
import com.linguareader.importer.ImportException
import com.linguareader.importer.ImportOptions
import com.linguareader.importer.ImportedBookBuilder
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer
import okio.use

/** Documento leído y listo para que el usuario confirme título, idioma y nivel. */
data class ImportDraft(
    val bookId: String,
    val sourceName: String,
    val document: ExtractedDocument,
    /** Idioma declarado por el documento, normalizado a BCP-47, si lo hay. */
    val detectedLanguage: String?,
    val alreadyImported: Boolean,
    /** Fichero de origen, si su formato tiene visor propio (PDF): se guarda como referencia, sin copia. */
    val original: OriginalDocument? = null,
)

/**
 * Importación en dos pasos, común a todas las plataformas: `read*` obtiene el texto de un fichero local y
 * [commit] lo convierte en libro con las opciones confirmadas y lo guarda. El id deriva del contenido, así que
 * reimportar lo mismo reemplaza en lugar de duplicar. Las fuentes específicas de cada plataforma (selector de
 * ficheros, «Compartir», página web) copian su contenido con [withTempCopy] y terminan en [draft].
 */
class LocalBookImporter(
    private val fileSystem: FileSystem,
    /** Carpeta temporal para las copias que se extraen (los formatos ZIP necesitan acceso aleatorio). */
    private val cacheDir: Path,
    private val registry: ExtractorRegistry,
    private val repository: ImportedBookRepository,
    private val narrations: NarrationLibrary,
    /** Normaliza el idioma declarado por el documento ("en_US" → "en-US"); null si no es reconocible. */
    private val normalizeLanguage: (String?) -> String? = ::basicLanguageTag,
    private val nowNanos: () -> Long = { com.linguareader.core.platform.epochMillis() * 1_000_000 },
) {
    private val builder = ImportedBookBuilder()

    /** Fichero local de la app (EPUB de una biblioteca descargada, copia de un fichero elegido). */
    suspend fun readFile(path: Path, displayName: String = path.name, mimeType: String? = null): ImportDraft = withContext(ioDispatcher) {
        fileSystem.source(path).use { source ->
            withTempCopy(source, MAX_FILE_BYTES) { copy, hash ->
                draft(hash, displayName, registry.extract(DocumentSource(copy, displayName, mimeType, fileSystem)))
            }
        }
    }

    /**
     * @param stableId id fijo (sin prefijo) en lugar del derivado del contenido: los libros de una
     *   biblioteca conservan progreso, marcadores y vocabulario cuando se actualiza su texto.
     */
    suspend fun commit(
        draft: ImportDraft,
        title: String,
        author: String,
        language: String,
        level: CefrLevel,
        stableId: String? = null,
    ): String = withContext(ioDispatcher) {
        val bookId = stableId?.let { ImportedBookRepository.ID_PREFIX + it } ?: draft.bookId
        val book = builder.build(
            draft.document,
            ImportOptions(
                bookId = bookId,
                title = title.ifBlank { draft.document.title ?: draft.sourceName },
                authors = author.split(',').map(String::trim).filter(String::isNotEmpty),
                language = language,
                level = level,
                sourceName = draft.sourceName,
            ),
        )
        repository.save(book, draft.document.format, draft.original)
        bookId
    }

    suspend fun delete(bookId: String) {
        repository.delete(bookId)
        narrations.delete(bookId)
    }

    suspend fun draft(hash: String, name: String, document: ExtractedDocument): ImportDraft {
        val id = ImportedBookRepository.ID_PREFIX + hash.take(16)
        return ImportDraft(
            bookId = id,
            sourceName = name,
            document = document,
            detectedLanguage = normalizeLanguage(document.language),
            alreadyImported = repository.contains(id),
        )
    }

    /** Copia [source] a la caché calculando su sha256 (con límite de tamaño) y la borra al terminar. */
    suspend fun <T> withTempCopy(source: Source, limit: Long, block: suspend (Path, String) -> T): T {
        fileSystem.createDirectories(cacheDir)
        val copy = cacheDir / "import-${nowNanos()}"
        try {
            val digest = Sha256()
            val buffered = source.buffer()
            fileSystem.write(copy) {
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = buffered.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    total += n
                    if (total > limit) {
                        throw ImportException(ImportException.Reason.UNSUPPORTED_FORMAT, "El fichero supera ${limit / 1024 / 1024} MB")
                    }
                    digest.update(buffer, 0, n)
                    write(buffer, 0, n)
                }
            }
            return block(copy, digest.digest().toHexLower())
        } finally {
            fileSystem.delete(copy, mustExist = false)
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 200L * 1024 * 1024
        const val MAX_PAGE_BYTES = 10L * 1024 * 1024

        /** "en_US", "fr-fr" → "en-US", "fr-FR"; sin tablas de códigos de tres letras (Android las aporta con Locale). */
        fun basicLanguageTag(tag: String?): String? {
            val parts = tag?.trim()?.replace('_', '-')?.split('-')?.filter { it.isNotEmpty() } ?: return null
            val language = parts.firstOrNull()?.lowercase()?.takeIf { it.length in 2..3 && it.all(Char::isLetter) && it != "und" } ?: return null
            val region = parts.getOrNull(1)?.takeIf { it.length == 2 && it.all(Char::isLetter) }?.uppercase()
            return if (region != null) "$language-$region" else language
        }

        /** Hash del contenido para el id de un texto pegado o compartido. */
        fun idForText(text: String): String = ImportedBookRepository.ID_PREFIX + Hashing.sha256Hex(text).take(16)
    }
}
