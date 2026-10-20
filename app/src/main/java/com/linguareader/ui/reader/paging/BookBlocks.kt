package com.linguareader.ui.reader.paging

import com.linguareader.core.model.Book
import com.linguareader.core.model.DisplayMode

/** Convierte un libro en bloques de maquetación según el modo de visualización. */
object BookBlocks {

    /** Clave de la traducción de una frase en el mapa de traducciones del lector. */
    fun key(passageId: String, sentenceId: String) = "$passageId/$sentenceId"

    /**
     * @param translations traducciones disponibles ahora (por [key]); las que falten no ocupan sitio y se añaden
     *   al llegar (se vuelve a paginar manteniendo la posición).
     * @param imageHeight altura en px de una ilustración.
     */
    fun of(
        book: Book,
        mode: DisplayMode,
        translations: Map<String, String>,
        showImages: Boolean,
        imageHeight: (large: Boolean) -> Int,
    ): List<LayoutBlock> {
        val blocks = mutableListOf<LayoutBlock>()
        book.chapters.forEachIndexed { c, chapter ->
            val first = chapter.passages.firstOrNull() ?: return@forEachIndexed
            if (chapter.title.isNotBlank()) blocks += LayoutBlock.ChapterTitle(c, first.index, chapter.title)
            for (p in chapter.passages) {
                if (p.image != null && showImages) blocks += LayoutBlock.Image(c, p.index, imageHeight(false))
                when (mode) {
                    // «Traducción al tocar»: en páginas, la traducción sale en la ficha al tocar una palabra.
                    DisplayMode.TRANSLATION_ON_TAP -> blocks += LayoutBlock.Original(c, p.index, 0, p.text.length)
                    DisplayMode.TRANSLATION_BELOW -> for (s in p.sentences) {
                        blocks += LayoutBlock.Original(c, p.index, s.rangeStart, s.rangeEnd)
                        translations[key(p.id, s.id)]?.let { blocks += LayoutBlock.Translation(c, p.index, it, s.rangeEnd) }
                    }
                }
            }
        }
        return blocks
    }
}
