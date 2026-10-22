package com.linguareader.importer

import com.linguareader.core.content.BookManifestParser
import com.linguareader.core.model.Book
import com.linguareader.core.model.CefrLevel
import com.linguareader.importer.extract.TextExtractor
import com.linguareader.importer.layout.PageWord
import com.linguareader.importer.layout.WordBoxAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordBoxAlignerTest {

    private fun book(text: String): Book {
        val document = TextExtractor().parse(text, "t")
        val options = ImportOptions("u-0123456789ab", "t", emptyList(), "en", CefrLevel.B1, "t.pdf")
        return BookManifestParser.parse(ImportedBookBuilder().build(document, options).manifestJson())
    }

    /** Palabras del «PDF» en una línea por página, cada una con una caja distinta según su posición. */
    private fun pages(vararg pages: String): List<PageWord> = pages.flatMapIndexed { page, line ->
        line.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w -> PageWord(page, w, i / 100f, 0.1f, (i + 1) / 100f, 0.12f) }
    }

    private fun Book.tokenIds(vararg words: String): List<String> =
        passages.flatMap { it.tokens }.filter { it.text in words }.map { it.id }

    @Test
    fun `cabeceras y números de página del PDF se saltan sin perder la sincronía`() {
        val book = book("The boat left the harbour. Anna watched the town.")
        val words = pages("My Book 1 The boat left the", "My Book 2 harbour. Anna watched the town.")
        val boxes = WordBoxAligner.align(book, words)
        val tokens = book.passages.flatMap { it.tokens }
        assertEquals(tokens.size, boxes.size)
        // «harbour» está en la segunda página, después de su cabecera («My Book 2»).
        val harbour = boxes.getValue(book.tokenIds("harbour").single()).single()
        assertEquals(1, harbour.page)
        assertEquals(3 / 100f, harbour.left)
    }

    @Test
    fun `palabra partida por guion entre líneas tiene dos cajas`() {
        val book = book("She had never travelled alone.")
        val boxes = WordBoxAligner.align(book, pages("She had never travel-", "led alone."))
        val travelled = boxes.getValue(book.tokenIds("travelled").single())
        assertEquals(listOf(0, 1), travelled.map { it.page })
        assertEquals(5, boxes.size)
    }

    @Test
    fun `una palabra que falta en el PDF no arrastra a las siguientes a una coincidencia lejana`() {
        // «the» falta en el PDF justo ahí; el siguiente «the» está lejos y no debe usarse.
        val book = book("Look at the sea and the sky over the old town today.")
        val words = pages("Look at sea and the sky over the old town today.")
        val boxes = WordBoxAligner.align(book, words)
        val the = book.tokenIds("the")
        assertNull(boxes[the[0]])
        // Los demás conservan su caja y su orden.
        assertEquals(book.passages.flatMap { it.tokens }.size - 1, boxes.size)
        assertEquals(4 / 100f, boxes.getValue(the[1]).single().left)
    }

    @Test
    fun `ligaduras y mayúsculas no impiden la coincidencia`() {
        val book = book("The first office.")
        val boxes = WordBoxAligner.align(book, pages("THE ﬁrst oﬃce."))
        assertEquals(3, boxes.size)
    }
}
