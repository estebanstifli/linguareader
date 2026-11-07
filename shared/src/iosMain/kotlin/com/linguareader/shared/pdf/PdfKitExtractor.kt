package com.linguareader.shared.pdf

import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedDocument
import com.linguareader.importer.ExtractedImage
import com.linguareader.importer.ImportException
import com.linguareader.importer.extract.PageTextCleaner
import com.linguareader.importer.extract.TextExtractor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFDocumentAuthorAttribute
import platform.PDFKit.PDFDocumentTitleAttribute
import platform.PDFKit.kPDFDisplayBoxCropBox
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/**
 * PDF con capa de texto mediante PDFKit (el equivalente iOS de `PdfExtractor` con PdfBox en Android). Un PDF es
 * un dibujo de páginas, no un texto estructurado: se extrae página a página, se limpian cabeceras, pies y números
 * de página ([PageTextCleaner]) y se reconstruyen párrafos. Los PDF escaneados (solo imágenes) no tienen texto: se
 * avisa en lugar de importar un libro vacío. Es síncrono porque `ExtractorRegistry.extract` lo es.
 */
@OptIn(ExperimentalForeignApi::class)
class PdfKitExtractor : DocumentExtractor {
    override val id = "pdf"
    override val extensions = setOf("pdf")

    override fun sniff(source: DocumentSource): Boolean {
        val head = source.head
        val text = head.copyOfRange(0, minOf(1024, head.size)).decodeToString(throwOnInvalidSequence = false)
        return text.contains("%PDF-")
    }

    override fun extract(source: DocumentSource): ExtractedDocument {
        val document = PDFDocument(uRL = NSURL.fileURLWithPath(source.path.toString()))
            ?: throw ImportException(ImportException.Reason.CORRUPT, "PDFKit could not open the file")
        // Contraseña vacía: abre los PDF que solo restringen permisos (copiar/imprimir).
        if (document.isLocked() && !document.unlockWithPassword("")) {
            throw ImportException(ImportException.Reason.DRM_PROTECTED, "The PDF is password-protected.")
        }
        val pageCount = document.pageCount.toInt()
        val pages = (0 until pageCount).map { index ->
            // PDFKit da saltos de línea pero no párrafos: la línea en blanco la pone PageTextCleaner/TextExtractor por heurística.
            document.pageAtIndex(index.toULong())?.string?.replace("\r\n", "\n").orEmpty()
        }
        val letters = pages.sumOf { page -> page.count(Char::isLetter) }
        if (pageCount == 0 || letters < MIN_LETTERS_PER_PAGE * pages.size.coerceAtMost(10)) {
            throw ImportException(ImportException.Reason.NO_TEXT, "This PDF has no text (it looks scanned). A PDF with selectable text is needed.")
        }
        val attributes = document.documentAttributes as? Map<*, *>
        val title = (attributes?.get(PDFDocumentTitleAttribute) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val author = (attributes?.get(PDFDocumentAuthorAttribute) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return TextExtractor().parse(PageTextCleaner.clean(pages.map(::paragraphs)), source.titleFromName, blankLineParagraphs = true).copy(
            format = id,
            title = title ?: source.titleFromName,
            authors = listOfNotNull(author),
            cover = firstPage(document),
            pageCount = pageCount,
        )
    }

    /**
     * Separa párrafos en el texto de una página de PDFKit (solo saltos de línea): línea que termina en puntuación de
     * cierre seguida de una que empieza en mayúscula, o línea claramente más corta que la mediana (final de párrafo).
     */
    private fun paragraphs(page: String): String {
        val lines = page.split('\n').map(String::trimEnd)
        val lengths = lines.filter { it.isNotBlank() }.map { it.length }.sorted()
        if (lengths.isEmpty()) return page
        val median = lengths[lengths.size / 2]
        val out = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            out.append(line).append('\n')
            val next = lines.getOrNull(i + 1) ?: continue
            if (line.isBlank() || next.isBlank()) continue
            val endsSentence = line.trimEnd().lastOrNull()?.let { it in ".!?…»”\"" } == true
            val nextStartsUpper = next.trimStart().firstOrNull()?.isUpperCase() == true
            val short = line.length < median * 0.6
            if ((endsSentence && nextStartsUpper) || (short && endsSentence) || (short && line.length < median * 0.4)) out.append('\n')
        }
        return out.toString()
    }

    /** Primera página reducida como portada (JPEG), como `PdfFirstPage` en Android. */
    private fun firstPage(document: PDFDocument): ExtractedImage? {
        val page = document.pageAtIndex(0u) ?: return null
        val image = page.thumbnailOfSize(CGSizeMake(480.0, 720.0), forBox = kPDFDisplayBoxCropBox)
        val data = UIImageJPEGRepresentation(image, 0.85) ?: return null
        val length = data.length.toInt()
        if (length == 0) return null
        val bytes = ByteArray(length)
        bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return ExtractedImage(bytes, "image/jpeg")
    }

    private companion object {
        const val MIN_LETTERS_PER_PAGE = 20
    }
}
