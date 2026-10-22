package com.linguareader.shared

import com.linguareader.core.content.Hashing
import com.linguareader.core.model.CefrLevel
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractorRegistry
import com.linguareader.importer.ImportOptions
import com.linguareader.importer.ImportedBookBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Importación de documentos en iOS con el extractor compartido (`:importer`): el fichero se convierte en
 * `manifest.json` + media en `<root>/<id>/` y el catálogo se refresca. Mismo resultado que `BookImportService`
 * en Android, sin el diálogo de confirmación (los metadatos vienen del documento o de los parámetros).
 */
class BookImporter(
    private val fs: FileSystem,
    private val root: Path,
    private val repository: DirectoryBookRepository,
) {
    private val registry = ExtractorRegistry.default()
    private val builder = ImportedBookBuilder()

    /** Devuelve el id del libro. Si ya estaba importado (mismo nombre y tamaño), no lo repite. */
    suspend fun importFile(path: String, displayName: String, language: String?, level: CefrLevel?): String =
        withContext(Dispatchers.IO) {
            val source = DocumentSource(path.toPath(), displayName, null, fs)
            val id = ID_PREFIX + Hashing.sha256Hex("$displayName|${source.sizeBytes}").take(12)
            val dir = root / id
            if (fs.exists(dir / "manifest.json")) return@withContext id
            val document = registry.extract(source)
            val book = builder.build(
                document,
                ImportOptions(
                    bookId = id,
                    title = document.title ?: source.titleFromName,
                    authors = document.authors,
                    language = language ?: document.language ?: "en",
                    level = level ?: document.level ?: CefrLevel.B1,
                    sourceName = displayName,
                ),
            )
            val staging = root / ".$id.tmp"
            fs.deleteRecursively(staging)
            fs.createDirectories(staging)
            fs.write(staging / "manifest.json") { writeUtf8(book.manifestJson()) }
            book.media.forEach { (relative, bytes) ->
                val file = staging / relative
                file.parent?.let { fs.createDirectories(it) }
                fs.write(file) { write(bytes) }
            }
            fs.deleteRecursively(dir)
            fs.atomicMove(staging, dir)
            repository.refresh()
            id
        }

    private companion object {
        const val ID_PREFIX = "imported-"
    }
}
