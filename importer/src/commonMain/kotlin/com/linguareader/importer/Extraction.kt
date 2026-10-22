package com.linguareader.importer

import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.NarrationRef
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.use

/** Fichero a importar, ya copiado a almacenamiento local (los formatos ZIP necesitan acceso aleatorio). */
class DocumentSource(
    val path: Path,
    /** Nombre visible original ("Moby Dick.epub"); orienta la detección por extensión y el título por defecto. */
    val displayName: String? = null,
    val mimeType: String? = null,
    val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    val extension: String get() = (displayName ?: path.name).substringAfterLast('.', "").lowercase()

    /** Primeros bytes del fichero para detectar el formato por firma ("magic bytes"). */
    val head: ByteArray by lazy { readBytes(HEAD_SIZE.toLong()) }

    /** Tamaño del fichero en bytes (0 si no se puede leer). */
    val sizeBytes: Long get() = fileSystem.metadataOrNull(path)?.size ?: 0L

    /** Contenido completo (o los primeros [maxBytes]). */
    fun readBytes(maxBytes: Long = Long.MAX_VALUE): ByteArray = fileSystem.source(path).use { source ->
        val out = Buffer()
        var total = 0L
        while (total < maxBytes) {
            val n = source.read(out, minOf(64L * 1024, maxBytes - total))
            if (n < 0) break
            total += n
        }
        out.readByteArray()
    }

    val titleFromName: String
        get() = (displayName ?: path.name).substringBeforeLast('.').replace('_', ' ').trim()

    companion object {
        const val HEAD_SIZE = 4096
    }
}

class ExtractedImage(
    val bytes: ByteArray,
    /** MIME si se conoce ("image/jpeg"); si no, se deduce por la firma. */
    val mediaType: String? = null,
)

sealed interface ExtractedBlock {
    data class Paragraph(val text: String) : ExtractedBlock
    class Image(val image: ExtractedImage, val alt: String?) : ExtractedBlock
}

data class ExtractedChapter(
    val title: String?,
    val blocks: List<ExtractedBlock>,
)

/** Texto estructurado de un documento, independiente del formato de origen. */
data class ExtractedDocument(
    val format: String,
    val title: String?,
    val authors: List<String> = emptyList(),
    /** BCP-47 si el documento lo declara (dc:language, lang, <lang> en FB2...). */
    val language: String? = null,
    val chapters: List<ExtractedChapter>,
    val cover: ExtractedImage? = null,
    /** Nivel MCER si el documento lo declara (EPUB: meta schema:educationalLevel o dc:subject «CEFR A1»). */
    val level: CefrLevel? = null,
    /** Narración pregrabada declarada por el documento (EPUB de la biblioteca: meta linguareader:narration-*). */
    val narration: NarrationRef? = null,
    /** Páginas del original si el formato las tiene (PDF); orienta al visor del formato original. */
    val pageCount: Int? = null,
) {
    val paragraphCount: Int get() = chapters.sumOf { c -> c.blocks.count { it is ExtractedBlock.Paragraph } }
}

/**
 * Puerto de extracción: convierte un formato concreto en [ExtractedDocument]. Añadir un formato
 * nuevo es añadir una implementación al [ExtractorRegistry]; la UI y el lector no cambian.
 */
interface DocumentExtractor {
    /** Identificador estable del formato ("epub", "fb2"...). */
    val id: String
    /** Extensiones que reconoce (sin punto), para el selector de ficheros y como último recurso. */
    val extensions: Set<String>

    /** Firma del contenido; true si el fichero es de este formato. */
    fun sniff(source: DocumentSource): Boolean

    fun extract(source: DocumentSource): ExtractedDocument
}

class ImportException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { UNSUPPORTED_FORMAT, DRM_PROTECTED, NO_TEXT, CORRUPT }
}
