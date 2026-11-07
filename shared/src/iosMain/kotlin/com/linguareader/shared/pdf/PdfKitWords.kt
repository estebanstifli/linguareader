package com.linguareader.shared.pdf

import com.linguareader.core.model.Book
import com.linguareader.importer.layout.PageWord
import com.linguareader.importer.layout.TokenBox
import com.linguareader.importer.layout.WordBoxAligner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.Foundation.NSMakeRange
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxCropBox
import kotlin.math.abs

/** Caja en espacio de página de PDFKit (origen abajo a la izquierda): [bottom] < [top]. */
private class Box(val left: Double, val bottom: Double, val right: Double, val top: Double)

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
            // Palabras = tramos sin espacios de `string`; su caja sale de la selección del rango (lo que usa Vista Previa),
            // y si viniera vacía, de las cajas de sus caracteres.
            var i = 0
            val utf16 = text.length
            while (i < utf16) {
                if (text[i].isWhitespace()) { i++; continue }
                val start = i
                while (i < utf16 && !text[i].isWhitespace()) i++
                val length = i - start
                var box = page.selectionForRange(NSMakeRange(start.toULong(), length.toULong()))?.boundsForPage(page)
                    ?.useContents { Box(origin.x, origin.y, origin.x + size.width, origin.y + size.height) }
                if (box == null || box.right <= box.left || box.top <= box.bottom) {
                    var merged: Box? = null
                    for (c in start until i) {
                        val b = page.characterBoundsAtIndex(c.toLong()).useContents { Box(origin.x, origin.y, origin.x + size.width, origin.y + size.height) }
                        if (b.right <= b.left || b.top <= b.bottom) continue
                        merged = merged?.let { Box(minOf(it.left, b.left), minOf(it.bottom, b.bottom), maxOf(it.right, b.right), maxOf(it.top, b.top)) } ?: b
                    }
                    box = merged
                }
                if (box == null) continue
                words += PageWord(
                    page = index, text = text.substring(start, i),
                    left = ((box.left - cropX) / width).coerceIn(0.0, 1.0).toFloat(),
                    top = (1 - (box.top - cropY) / height).coerceIn(0.0, 1.0).toFloat(),
                    right = ((box.right - cropX) / width).coerceIn(0.0, 1.0).toFloat(),
                    bottom = (1 - (box.bottom - cropY) / height).coerceIn(0.0, 1.0).toFloat(),
                )
            }
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
