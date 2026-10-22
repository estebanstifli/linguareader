package com.linguareader.importer

import com.linguareader.core.platform.decodeLatin1
import com.linguareader.importer.extract.DocxExtractor
import com.linguareader.importer.extract.EpubExtractor
import com.linguareader.importer.extract.Fb2Extractor
import com.linguareader.importer.extract.HtmlExtractor
import com.linguareader.importer.extract.MarkdownExtractor
import com.linguareader.importer.extract.MobiExtractor
import com.linguareader.importer.extract.OdtExtractor
import com.linguareader.importer.extract.RtfExtractor
import com.linguareader.importer.extract.SubtitleExtractor
import com.linguareader.importer.extract.TextExtractor

/**
 * Elige el extractor adecuado: primero por firma del contenido (más fiable que el nombre, que
 * los gestores de ficheros a veces pierden) y después por extensión.
 */
class ExtractorRegistry(private val extractors: List<DocumentExtractor>) {

    val supportedExtensions: Set<String> get() = extractors.flatMap { it.extensions }.distinct().sorted().toSet()

    fun find(source: DocumentSource): DocumentExtractor? =
        extractors.firstOrNull { runCatching { it.sniff(source) }.getOrDefault(false) }
            ?: extractors.firstOrNull { source.extension in it.extensions }

    fun extract(source: DocumentSource): ExtractedDocument {
        val extractor = find(source) ?: throw ImportException(
            ImportException.Reason.UNSUPPORTED_FORMAT,
            KnownUnsupported.explain(source)
                ?: "Formato no soportado${source.extension.takeIf { it.isNotEmpty() }?.let { " (.$it)" } ?: ""}. " +
                "Se admiten: ${supportedExtensions.joinToString(", ")}.",
        )
        val document = try {
            extractor.extract(source)
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException(ImportException.Reason.CORRUPT, "No se pudo leer el fichero (${extractor.id}): ${e.message}", e)
        }
        if (document.paragraphCount == 0) {
            throw ImportException(ImportException.Reason.NO_TEXT, "El documento no contiene texto legible")
        }
        return document
    }

    companion object {
        /**
         * Extractores JVM puros. El orden importa: los de firma específica (ZIP con mimetype,
         * PDB, FictionBook, RTF) antes que los genéricos de texto. [extra] (p. ej. PDF, que
         * necesita Android) va delante.
         */
        fun default(extra: List<DocumentExtractor> = emptyList()): ExtractorRegistry = ExtractorRegistry(
            extra + listOf(
                EpubExtractor(),
                OdtExtractor(),
                DocxExtractor(),
                MobiExtractor(),
                Fb2Extractor(),
                RtfExtractor(),
                HtmlExtractor(),
                SubtitleExtractor(),
                MarkdownExtractor(),
                TextExtractor(),
            ),
        )
    }
}

/** Formatos reconocibles que no se importan, con una explicación útil en lugar de "no soportado". */
internal object KnownUnsupported {
    fun explain(source: DocumentSource): String? {
        val head = source.head
        val ascii = head.decodeLatin1(0, minOf(head.size, 64))
        return when {
            head.size >= 8 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte() ->
                "Los documentos de Word antiguos (.doc) no se pueden leer. Ábrelo en Word, LibreOffice o Google Docs y guárdalo como DOCX."
            ascii.startsWith("ITSF") ->
                "Los ficheros de ayuda CHM no están soportados. Puedes convertirlos a EPUB con Calibre."
            ascii.startsWith("AT&TFORM") ->
                "Los DjVu suelen ser páginas escaneadas sin texto, así que no se pueden leer en voz alta."
            (head.isNotEmpty() && head[0] == 0xEA.toByte() && ascii.startsWith("DRMION", 1)) || source.extension == "kfx" ||
                (source.extension in setOf("azw", "azw8") && ascii.startsWith("CONT")) ->
                "Los libros KFX de Kindle (formato moderno de Amazon) no se pueden importar."
            source.extension in setOf("cbz", "cbr", "cb7") ->
                "Los cómics son imágenes sin texto que leer."
            source.extension in setOf("mp3", "m4a", "m4b", "ogg", "wav") ->
                "Los audiolibros no se pueden importar: hace falta el texto."
            else -> null
        }
    }
}
