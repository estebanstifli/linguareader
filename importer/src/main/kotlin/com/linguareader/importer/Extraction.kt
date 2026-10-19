package com.linguareader.importer

import com.linguareader.core.model.CefrLevel
import java.io.File

/** Fichero a importar, ya copiado a almacenamiento local (los formatos ZIP necesitan acceso aleatorio). */
class DocumentSource(
    val file: File,
    /** Nombre visible original ("Moby Dick.epub"); orienta la detección por extensión y el título por defecto. */
    val displayName: String? = null,
    val mimeType: String? = null,
) {
    val extension: String get() = (displayName ?: file.name).substringAfterLast('.', "").lowercase()

    /** Primeros bytes del fichero para detectar el formato por firma ("magic bytes"). */
    val head: ByteArray by lazy {
        file.inputStream().use { input ->
            val buffer = ByteArray(HEAD_SIZE)
            var read = 0
            while (read < HEAD_SIZE) {
                val n = input.read(buffer, read, HEAD_SIZE - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    }

    val titleFromName: String
        get() = (displayName ?: file.name).substringBeforeLast('.').replace('_', ' ').trim()

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
