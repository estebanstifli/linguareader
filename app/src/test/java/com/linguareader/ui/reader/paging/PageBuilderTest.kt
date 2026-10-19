package com.linguareader.ui.reader.paging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBuilderTest {

    /** Texto [start, end) de un trozo original (lo que importa para la lectura). */
    private fun PageItem.range() = (this as PageItem.Original).let { it.passage to (it.start until it.end) }

    /** Texto falso: [charsPerLine] caracteres por línea, cada línea mide [lineHeight] px. */
    private class FakeText(private val length: Int, private val charsPerLine: Int, private val lineHeight: Float = 10f) : MeasuredText {
        override val lineCount = (length + charsPerLine - 1) / charsPerLine
        override fun lineTop(line: Int) = line * lineHeight
        override fun lineBottom(line: Int) = (line + 1) * lineHeight
        override fun lineStart(line: Int) = line * charsPerLine
        override fun lineEnd(line: Int) = minOf(length, (line + 1) * charsPerLine)
    }

    private fun measureBy(charsPerLine: Int): (LayoutBlock) -> MeasuredText? = { b ->
        when (b) {
            is LayoutBlock.Original -> FakeText(b.end - b.start, charsPerLine)
            is LayoutBlock.Translation -> FakeText(b.text.length, charsPerLine)
            is LayoutBlock.ChapterTitle -> FakeText(b.text.length, 100, lineHeight = 20f)
            is LayoutBlock.Image -> null
        }
    }

    @Test
    fun `a long text is cut by whole lines and pages continue where the previous ended`() {
        // 25 líneas de 10 px en páginas de 100 px → 10 + 10 + 5 líneas.
        val pages = PageBuilder.build(listOf(LayoutBlock.Original(0, 0, 0, 250)), measureBy(10), 100f, 0f, 0f)
        assertEquals(3, pages.size)
        assertEquals(0 to (0 until 100), pages[0].items.single().range())
        assertEquals(0 to (100 until 200), pages[1].items.single().range())
        assertEquals(0 to (200 until 250), pages[2].items.single().range())
        // Cada trozo sabe qué líneas del bloque entero muestra (para dibujarlo justificado sin descuadres).
        val last = pages[2].items.single() as PageItem.Original
        assertEquals(listOf(0, 250, 20, 24), listOf(last.blockStart, last.blockEnd, last.firstLine, last.lastLine))
        assertEquals(listOf(TextPos(0, 0), TextPos(0, 100), TextPos(0, 200)), pages.map { it.start })
    }

    @Test
    fun `passages flow on the same page and chapters start a new one`() {
        val blocks = listOf(
            LayoutBlock.ChapterTitle(0, 0, "One"),
            LayoutBlock.Original(0, 0, 0, 30), // 3 líneas
            LayoutBlock.Original(0, 1, 0, 30), // 3 líneas, mismo capítulo: misma página
            LayoutBlock.ChapterTitle(1, 2, "Two"),
            LayoutBlock.Original(1, 2, 0, 10),
        )
        val pages = PageBuilder.build(blocks, measureBy(10), 100f, 4f, 8f)
        assertEquals(2, pages.size)
        assertEquals(3, pages[0].items.size)
        assertEquals(0, pages[0].chapter)
        assertEquals(1, pages[1].chapter)
        assertEquals(TextPos(2, 0), pages[1].start)
    }

    @Test
    fun `an image that does not fit goes to the next page`() {
        val blocks = listOf(
            LayoutBlock.Original(0, 0, 0, 80), // 80 px
            LayoutBlock.Image(0, 1, 50),
            LayoutBlock.Original(0, 1, 0, 10),
        )
        val pages = PageBuilder.build(blocks, measureBy(10), 100f, 0f, 0f)
        assertEquals(2, pages.size)
        assertTrue(pages[1].items.first() is PageItem.Image)
        assertEquals(TextPos(1, 0), pages[1].start)
    }

    @Test
    fun `spacing counts against the page height`() {
        // Dos bloques de 5 líneas (50 px) + 10 px de separación no caben en 100 px: el segundo se parte.
        val blocks = listOf(LayoutBlock.Original(0, 0, 0, 50), LayoutBlock.Original(0, 1, 0, 50))
        val pages = PageBuilder.build(blocks, measureBy(10), 100f, 10f, 0f)
        assertEquals(1 to (0 until 40), pages[0].items[1].range())
        assertEquals(1 to (40 until 50), pages[1].items.single().range())
    }

    @Test
    fun `page lookup by text position`() {
        val pages = PageBuilder.build(listOf(LayoutBlock.Original(0, 0, 0, 250), LayoutBlock.Original(0, 1, 0, 10)), measureBy(10), 100f, 0f, 0f)
        val book = PagedBook(pages)
        assertEquals(0, book.pageOf(TextPos(0, 0)))
        assertEquals(0, book.pageOf(TextPos(0, 99)))
        assertEquals(1, book.pageOf(TextPos(0, 100)))
        assertEquals(2, book.pageOf(TextPos(1, 5)))
        assertEquals(0..2, book.chapterRange(1))
    }

    @Test
    fun `a page that starts with a translation starts at the end of its original`() {
        val blocks = listOf(
            LayoutBlock.Original(0, 0, 0, 100), // 10 líneas: llena la página
            LayoutBlock.Translation(0, 0, "x".repeat(20), 100),
        )
        val pages = PageBuilder.build(blocks, measureBy(10), 100f, 0f, 0f)
        assertEquals(2, pages.size)
        assertEquals(TextPos(0, 100), pages[1].start)
    }
}
