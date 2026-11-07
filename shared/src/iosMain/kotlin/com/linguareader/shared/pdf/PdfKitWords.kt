package com.linguareader.shared.pdf

import com.linguareader.core.model.Book
import com.linguareader.importer.layout.PageWord
import com.linguareader.importer.layout.TokenBox
import com.linguareader.importer.layout.WordBoxAligner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxCropBox
import kotlin.math.abs

/**
 * Palabras de cada página de un PDF con su caja, en el mismo orden en que [PdfKitExtractor] obtiene el texto
 * (`PDFPage.string`), como `PdfWordExtractor` en Android. Las cajas van en fracciones de la CropBox **sin girar**
 * (espacio de página de PDFKit, origen abajo a la izquierda convertido a «desde arriba»): `PDFView.convert(_:from:)`
 * aplica la rotación al dibujarlas.
 */
@OptIn(ExperimentalForeignApi::class)
object PdfKitWordExtractor {

    /** @param onProgress páginas procesadas y total. */
    suspend fun extract(path: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<PageWord> {
        val document = PDFDocument(uRL = NSURL.fileURLWithPath(path)) ?: return emptyList()
        if (document.isLocked()) document.unlockWithPassword("")
        val total = document.pageCount.toInt()
        val words = ArrayList<PageWord>()
        for (index in 0 until total) {
            currentCoroutineContext().ensureActive()
            val page = document.pageAtIndex(index.toULong()) ?: continue
            val text = page.string ?: continue
            val (cropX, cropY, cropW, cropH) = page.boundsForBox(kPDFDisplayBoxCropBox).useContents { listOf(origin.x, origin.y, size.width, size.height) }
            val width = cropW.takeIf { it > 0 } ?: 1.0
            val height = cropH.takeIf { it > 0 } ?: 1.0
            var current: StringBuilder? = null
            var left = 0.0; var top = 0.0; var right = 0.0; var bottom = 0.0
            fun flush() {
                val word = current ?: return
                if (right > left && bottom > top) {
                    words += PageWord(
                        page = index, text = word.toString(),
                        left = ((left - cropX) / width).coerceIn(0.0, 1.0).toFloat(),
                        top = (1 - (bottom - cropY) / height).coerceIn(0.0, 1.0).toFloat(),
                        right = ((right - cropX) / width).coerceIn(0.0, 1.0).toFloat(),
                        bottom = (1 - (top - cropY) / height).coerceIn(0.0, 1.0).toFloat(),
                    )
                }
                current = null
            }
            // El índice de carácter de PDFKit es UTF-16 (NSString): se recorre así.
            var i = 0
            val utf16 = text.length
            while (i < utf16) {
                val ch = text[i]
                if (ch.isWhitespace()) {
                    flush()
                    i++
                    continue
                }
                val (x0, y0, w, h) = page.characterBoundsAtIndex(i.toLong()).useContents { listOf(origin.x, origin.y, size.width, size.height) }
                if (w <= 0 || h <= 0) { i++; continue }
                val x1 = x0 + w
                val y1 = y0 + h
                if (current == null) {
                    current = StringBuilder().append(ch)
                    left = x0; top = y0; right = x1; bottom = y1
                } else {
                    // Salto grande en x o y dentro de una «palabra» sin espacio (cambio de columna): se parte.
                    if (abs(y0 - top) > h * 1.5 || x0 < left - w) { flush(); current = StringBuilder().append(ch); left = x0; top = y0; right = x1; bottom = y1 }
                    else { current!!.append(ch); left = minOf(left, x0); top = minOf(top, y0); right = maxOf(right, x1); bottom = maxOf(bottom, y1) }
                }
                i++
            }
            flush()
            onProgress(index + 1, total)
        }
        return words
    }
}

/** Caja de un token sobre una página del PDF, como la ve Swift (fracciones de la CropBox sin girar, y desde arriba). */
class PdfTokenBox(val tokenId: String, val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Posición de cada token del libro sobre las páginas del PDF original (mismo papel que `PdfTextLayout` en Android):
 * cajas por token, tokens por página, token bajo un punto y cobertura.
 */
class PdfWordLayout(private val tokens: Map<String, List<TokenBox>>) {

    /** Tokens de cada página, en orden de lectura (para tocar y para saber dónde empieza la página). */
    private val byPage: Map<Int, List<Pair<String, TokenBox>>> =
        tokens.entries.flatMap { (id, boxes) -> boxes.map { it.page to (id to it) } }.groupBy({ it.first }, { it.second })

    val tokenCount: Int get() = tokens.size

    fun boxes(tokenId: String): List<PdfTokenBox> = tokens[tokenId].orEmpty().map { PdfTokenBox(tokenId, it.page, it.left, it.top, it.right, it.bottom) }

    /** Página de la primera caja de un token, o -1. */
    fun pageOf(tokenId: String): Int = tokens[tokenId]?.firstOrNull()?.page ?: -1

    /** Token bajo el punto ([x], [y] en fracciones de la página), con un pequeño margen para dedos. */
    fun hit(page: Int, x: Float, y: Float, slopX: Float = 0.01f, slopY: Float = 0.006f): String? {
        val boxes = byPage[page] ?: return null
        boxes.firstOrNull { (_, b) -> x in b.left..b.right && y in b.top..b.bottom }?.let { return it.first }
        return boxes
            .filter { (_, b) -> x in (b.left - slopX)..(b.right + slopX) && y in (b.top - slopY)..(b.bottom + slopY) }
            .minByOrNull { (_, b) -> abs((b.left + b.right) / 2 - x) }?.first
    }

    fun firstTokenOn(page: Int): String? = byPage[page]?.firstOrNull()?.first

    /** Fracción de tokens del libro con caja (calidad de la correspondencia). */
    fun coverage(book: Book): Float = tokens.size.toFloat() / book.passages.sumOf { it.tokens.size }.coerceAtLeast(1)

    companion object {
        suspend fun compute(book: Book, pdfPath: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): PdfWordLayout =
            PdfWordLayout(WordBoxAligner.align(book, PdfKitWordExtractor.extract(pdfPath, onProgress)))
    }
}
