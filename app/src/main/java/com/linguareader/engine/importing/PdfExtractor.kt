package com.linguareader.engine.importing

import android.content.Context
import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedDocument
import com.linguareader.importer.ImportException
import com.linguareader.importer.extract.PageTextCleaner
import com.linguareader.importer.extract.TextExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * PDF con capa de texto, mediante PdfBox-Android (Apache-2.0). Un PDF es un dibujo de páginas,
 * no un texto estructurado: se extrae página a página, se limpian cabeceras, pies y números de
 * página ([PageTextCleaner]) y se reconstruyen párrafos. Los PDF escaneados (solo imágenes)
 * no tienen texto: se avisa en lugar de importar un libro vacío.
 */
class PdfExtractor(private val context: Context) : DocumentExtractor {
    override val id = "pdf"
    override val extensions = setOf("pdf")

    override fun sniff(source: DocumentSource): Boolean =
        String(source.head, 0, minOf(1024, source.head.size), Charsets.ISO_8859_1).contains("%PDF-")

    override fun extract(source: DocumentSource): ExtractedDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
        val document = try {
            // Contraseña vacía: abre los PDF que solo restringen permisos (copiar/imprimir).
            PDDocument.load(source.file, "", MemoryUsageSetting.setupMixed(MAX_MEMORY_BYTES))
        } catch (_: InvalidPasswordException) {
            throw ImportException(ImportException.Reason.DRM_PROTECTED, "El PDF está protegido con contraseña.")
        }
        return document.use { pdf ->
            val stripper = PDFTextStripper().apply {
                // Línea en blanco antes de cada párrafo detectado; las líneas internas las une TextExtractor.
                paragraphStart = "\n"
                lineSeparator = "\n"
                pageEnd = ""
            }
            val pages = (1..pdf.numberOfPages).map { page ->
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(pdf)
            }
            val letters = pages.sumOf { page -> page.count(Char::isLetter) }
            if (letters < MIN_LETTERS_PER_PAGE * pages.size.coerceAtMost(10)) {
                throw ImportException(
                    ImportException.Reason.NO_TEXT,
                    "Este PDF no tiene texto (parece escaneado). Hace falta un PDF con texto seleccionable.",
                )
            }
            val info = pdf.documentInformation
            TextExtractor().parse(PageTextCleaner.clean(pages), source.titleFromName, blankLineParagraphs = true).copy(
                format = id,
                title = info?.title?.trim()?.takeIf { it.isNotEmpty() } ?: source.titleFromName,
                authors = listOfNotNull(info?.author?.trim()?.takeIf { it.isNotEmpty() }),
                language = pdf.documentCatalog?.language?.trim()?.takeIf { it.isNotEmpty() },
                cover = PdfFirstPage.render(source.file),
                pageCount = pdf.numberOfPages,
            )
        }
    }

    private companion object {
        const val MAX_MEMORY_BYTES = 64L * 1024 * 1024
        const val MIN_LETTERS_PER_PAGE = 20
    }
}
