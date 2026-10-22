package com.linguareader.engine.importing

import android.content.Context
import com.linguareader.importer.layout.PageWord
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

/**
 * Palabras de cada página de un PDF con su caja, en el mismo orden en que [PdfExtractor] obtiene el
 * texto del libro (mismos ajustes de PdfBox). Las cajas se expresan en fracciones de la página
 * visible (CropBox, la que dibuja `PdfRenderer`), así que valen para cualquier escala de zoom.
 */
class PdfWordExtractor(private val context: Context) {

    /** @param onProgress páginas procesadas y total, para la barra «Preparando…». */
    suspend fun extract(input: InputStream, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<PageWord> {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(input, "", MemoryUsageSetting.setupMixed(MAX_MEMORY_BYTES)).use { pdf ->
            val stripper = BoxStripper()
            val total = pdf.numberOfPages
            for (page in 1..total) {
                currentCoroutineContext().ensureActive()
                stripper.pageIndex = page - 1
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(pdf)
                onProgress(page, total)
            }
            stripper.words
        }
    }

    private class BoxStripper : PDFTextStripper() {
        val words = ArrayList<PageWord>()
        var pageIndex = 0
        private var width = 1f
        private var height = 1f

        init {
            // Los mismos ajustes que la importación: el orden de las palabras coincide con el del texto del libro.
            paragraphStart = "\n"
            lineSeparator = "\n"
            pageEnd = ""
        }

        override fun startPage(page: PDPage) {
            super.startPage(page)
            // PdfBox ya da las posiciones relativas a la CropBox (la página que se ve) y giradas como se muestra.
            val crop = page.cropBox
            val rotated = page.rotation % 180 != 0
            width = (if (rotated) crop.height else crop.width).coerceAtLeast(1f)
            height = (if (rotated) crop.width else crop.height).coerceAtLeast(1f)
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            var current: StringBuilder? = null
            var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
            fun flush() {
                val word = current ?: return
                words += PageWord(
                    page = pageIndex,
                    text = word.toString(),
                    left = (left / width).coerceIn(0f, 1f),
                    top = (top / height).coerceIn(0f, 1f),
                    right = (right / width).coerceIn(0f, 1f),
                    bottom = (bottom / height).coerceIn(0f, 1f),
                )
                current = null
            }
            for (p in textPositions) {
                val unicode = p.unicode ?: continue
                if (unicode.isBlank()) {
                    flush()
                    continue
                }
                // Coordenadas con la rotación de la página aplicada; y es la línea base, desde arriba.
                val h = p.height.takeIf { it > 0f } ?: p.fontSizeInPt
                val x0 = p.x
                val x1 = x0 + p.width
                // La altura es la de las mayúsculas: se amplía un poco para cubrir acentos y descendentes.
                val y0 = p.y - h * 1.2f
                val y1 = p.y + h * 0.3f
                val word = current
                if (word == null) {
                    current = StringBuilder(unicode)
                    left = x0; top = y0; right = x1; bottom = y1
                } else {
                    word.append(unicode)
                    left = minOf(left, x0); top = minOf(top, y0); right = maxOf(right, x1); bottom = maxOf(bottom, y1)
                }
            }
            flush()
        }
    }

    private companion object {
        const val MAX_MEMORY_BYTES = 64L * 1024 * 1024
    }
}
