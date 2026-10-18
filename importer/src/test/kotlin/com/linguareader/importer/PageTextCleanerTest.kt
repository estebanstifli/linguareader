package com.linguareader.importer

import com.linguareader.importer.extract.PageTextCleaner
import com.linguareader.importer.extract.TextExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTextCleanerTest {

    private fun paragraphs(text: String) = TextExtractor().parse(text, "x", blankLineParagraphs = true)
        .chapters.flatMap { c -> c.blocks.filterIsInstance<ExtractedBlock.Paragraph>().map { it.text } }

    @Test
    fun `quita cabeceras y pies repetidos y números de página, y une párrafos entre páginas`() {
        val pages = listOf(
            "El faro\n\nLa noche era larga y el viento\nsoplaba sin parar sobre la\n\nCapítulo 1 · 1",
            "El faro\n\ncosta. Nadie dormía.\n\nAl alba, el mar se calmó y los barcos con-\n\nCapítulo 1 · 2",
            "El faro\n\ntinuaron su camino.\n\nCapítulo 1 · 3\nPágina 3 de 4",
            "El faro\n\nFin de la historia.\n\nCapítulo 1 · 4\n- 4 -",
        )
        assertEquals(
            listOf(
                "La noche era larga y el viento soplaba sin parar sobre la costa. Nadie dormía.",
                "Al alba, el mar se calmó y los barcos continuaron su camino.",
                "Fin de la historia.",
            ),
            paragraphs(PageTextCleaner.clean(pages)),
        )
    }

    @Test
    fun `con pocas páginas no se descartan líneas que solo parecen cabeceras`() {
        val pages = listOf("Introducción\n\nTexto uno.", "Introducción\n\nTexto dos.")
        assertEquals(listOf("Introducción", "Texto uno.", "Introducción", "Texto dos."), paragraphs(PageTextCleaner.clean(pages)))
    }
}
