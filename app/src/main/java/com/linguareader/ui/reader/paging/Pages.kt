package com.linguareader.ui.reader.paging

/** Posición en el texto de un libro: pasaje (índice global) y offset de carácter en su texto. */
data class TextPos(val passage: Int, val offset: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos): Int =
        if (passage != other.passage) passage.compareTo(other.passage) else offset.compareTo(other.offset)
}

/** Unidad de maquetación antes de paginar. Cada bloque pertenece a un capítulo y a un pasaje. */
sealed interface LayoutBlock {
    val chapter: Int
    val passage: Int

    /** Título de capítulo: siempre abre página nueva. */
    data class ChapterTitle(override val chapter: Int, override val passage: Int, val text: String) : LayoutBlock

    /** Ilustración del pasaje, con altura fija (ancho de página / relación de aspecto). */
    data class Image(override val chapter: Int, override val passage: Int, val heightPx: Int) : LayoutBlock

    /** Texto original [start, end) del pasaje (offsets sobre `Passage.text`). */
    data class Original(override val chapter: Int, override val passage: Int, val start: Int, val end: Int) : LayoutBlock

    /** Traducción que acompaña al texto original anterior (de una frase o del pasaje entero). */
    data class Translation(override val chapter: Int, override val passage: Int, val text: String, val position: Int) : LayoutBlock
}

/**
 * Lo que se dibuja en una página: trozos de bloques cortados por líneas. Los trozos de texto se dibujan maquetando
 * el bloque **entero** (igual que al medir) y recortando sus líneas [firstLine]..[lastLine]: así el justificado,
 * el guionado y el reparto de espacios son idénticos a lo medido, y la última línea de la página queda justificada.
 */
sealed interface PageItem {
    val passage: Int

    data class Title(override val passage: Int, val text: String) : PageItem
    data class Image(override val passage: Int, val heightPx: Int) : PageItem

    /** Texto [start, end) del pasaje, que ocupa las líneas indicadas del bloque [blockStart, blockEnd). */
    data class Original(
        override val passage: Int,
        val start: Int,
        val end: Int,
        val blockStart: Int,
        val blockEnd: Int,
        val firstLine: Int,
        val lastLine: Int,
    ) : PageItem

    /** Líneas [firstLine]..[lastLine] de la traducción [text] (entera). */
    data class Translation(override val passage: Int, val text: String, val firstLine: Int, val lastLine: Int) : PageItem
}

/** Una página: sus elementos, la posición del texto en la que empieza y su capítulo. */
data class Page(val items: List<PageItem>, val start: TextPos, val chapter: Int)

/** Métricas de líneas de un texto ya maquetado al ancho de página (en Compose, un `TextLayoutResult`). */
interface MeasuredText {
    val lineCount: Int
    fun lineTop(line: Int): Float
    fun lineBottom(line: Int): Float
    /** Offset (en el texto medido) donde empieza la línea. */
    fun lineStart(line: Int): Int
    /** Offset (exclusivo) donde termina la línea, incluido el espacio final. */
    fun lineEnd(line: Int): Int
}

/** Libro ya paginado. Búsquedas por posición en O(log n). */
class PagedBook(val pages: List<Page>) {
    val size: Int get() = pages.size

    /** Página que contiene [pos]: la última que empieza en o antes de esa posición. */
    fun pageOf(pos: TextPos): Int {
        if (pages.isEmpty()) return 0
        var lo = 0
        var hi = pages.lastIndex
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (pages[mid].start <= pos) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** Páginas (índices globales) del capítulo de la página [page]. */
    fun chapterRange(page: Int): IntRange {
        val chapter = pages.getOrNull(page)?.chapter ?: return IntRange.EMPTY
        var first = page
        while (first > 0 && pages[first - 1].chapter == chapter) first--
        var last = page
        while (last < pages.lastIndex && pages[last + 1].chapter == chapter) last++
        return first..last
    }

    /** Fin (exclusivo) del texto de la página [page]: el inicio de la siguiente. */
    fun endOf(page: Int): TextPos = pages.getOrNull(page + 1)?.start ?: TextPos(Int.MAX_VALUE, 0)
}

/**
 * Corta los bloques en páginas de [pageHeight] px. Los bloques de texto se parten por líneas (nunca a mitad de
 * línea); si no cabe ni una línea en lo que queda de página, pasa a la siguiente. Cada título de capítulo abre
 * página. Una imagen que no cabe en lo que queda pasa a la siguiente (y si es más alta que la página, se limita).
 * Entre bloques se deja [blockSpacing] px; tras un título, [titleSpacing].
 */
object PageBuilder {

    fun build(
        blocks: List<LayoutBlock>,
        measure: (LayoutBlock) -> MeasuredText?,
        pageHeight: Float,
        blockSpacing: Float,
        titleSpacing: Float,
    ): List<Page> {
        val pages = mutableListOf<Page>()
        var items = mutableListOf<PageItem>()
        var used = 0f
        var start: TextPos? = null
        var chapter = 0
        // Posición del último texto colocado: una página que empieza con una traducción empieza ahí.
        var lastPos = TextPos(0, 0)

        fun flush() {
            if (items.isEmpty()) return
            pages += Page(items, start ?: lastPos, chapter)
            items = mutableListOf()
            used = 0f
            start = null
        }

        fun place(item: PageItem, height: Float, spacing: Float, pos: TextPos, blockChapter: Int) {
            if (items.isEmpty()) { start = pos; chapter = blockChapter }
            items += item
            used += height + spacing
        }

        for (block in blocks) {
            when (block) {
                is LayoutBlock.ChapterTitle -> {
                    flush()
                    val m = measure(block)
                    val h = m?.let { it.lineBottom(it.lineCount - 1) - it.lineTop(0) } ?: 0f
                    lastPos = TextPos(block.passage, 0)
                    place(PageItem.Title(block.passage, block.text), minOf(h, pageHeight), titleSpacing, lastPos, block.chapter)
                }
                is LayoutBlock.Image -> {
                    val h = minOf(block.heightPx.toFloat(), pageHeight)
                    if (items.isNotEmpty() && used + h > pageHeight) flush()
                    lastPos = TextPos(block.passage, 0)
                    place(PageItem.Image(block.passage, h.toInt()), h, blockSpacing, lastPos, block.chapter)
                }
                is LayoutBlock.Original, is LayoutBlock.Translation -> {
                    val m = measure(block) ?: continue
                    var line = 0
                    while (line < m.lineCount) {
                        val available = pageHeight - used
                        val top = m.lineTop(line)
                        var last = line - 1
                        while (last + 1 < m.lineCount && m.lineBottom(last + 1) - top <= available) last++
                        if (last < line) {
                            if (items.isNotEmpty()) { flush(); continue }
                            last = line // ni una línea cabe en una página vacía: se coloca igualmente
                        }
                        val from = m.lineStart(line)
                        val to = m.lineEnd(last)
                        val height = m.lineBottom(last) - top
                        when (block) {
                            is LayoutBlock.Original -> {
                                val pos = TextPos(block.passage, block.start + from)
                                place(
                                    PageItem.Original(block.passage, block.start + from, block.start + to, block.start, block.end, line, last),
                                    height, blockSpacing, pos, block.chapter,
                                )
                                lastPos = TextPos(block.passage, block.start + to)
                            }
                            is LayoutBlock.Translation -> {
                                val pos = TextPos(block.passage, block.position)
                                place(PageItem.Translation(block.passage, block.text, line, last), height, blockSpacing, pos, block.chapter)
                            }
                            else -> Unit
                        }
                        line = last + 1
                        if (line < m.lineCount) flush()
                    }
                }
            }
        }
        flush()
        return pages
    }
}
