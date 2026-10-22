package com.linguareader.importer

import com.linguareader.core.model.CefrLevel
import com.linguareader.importer.extract.EpubExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * EPUB de la biblioteca generado por tools/build_epub.py (es/A1/el-sastrecillo-valiente, texto propio):
 * el importador debe leer metadatos, nivel MCER, portada y los 8 capítulos con su título.
 */
class LibraryEpubTest {

    @get:Rule val folder = TemporaryFolder()

    private fun document(): ExtractedDocument {
        val file = folder.newFile("el-sastrecillo-valiente.epub")
        javaClass.getResourceAsStream("/library/el-sastrecillo-valiente.epub")!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val source = DocumentSource(file, "el-sastrecillo-valiente.epub", "application/epub+zip")
        val extractor = EpubExtractor()
        assertTrue(extractor.sniff(source))
        return extractor.extract(source)
    }

    @Test
    fun readsMetadataLevelAndCover() {
        val doc = document()
        assertEquals("El sastrecillo valiente", doc.title)
        assertEquals(listOf("Hermanos Grimm"), doc.authors)
        assertEquals("es", doc.language)
        assertEquals(CefrLevel.A1, doc.level)
        assertNotNull(doc.cover)
    }

    @Test
    fun readsEightChaptersWithTheirTitles() {
        val chapters = document().chapters
        assertEquals(8, chapters.size)
        assertEquals("Mermelada y moscas", chapters.first().title)
        assertEquals("El sastre habla en sueños", chapters.last().title)
        val first = chapters.first().blocks.filterIsInstance<ExtractedBlock.Paragraph>()
        assertTrue(first.first().text.startsWith("Es una mañana de sol"))
        // El número de capítulo del encabezado no se cuela como párrafo.
        assertFalse(first.any { it.text.startsWith("Capítulo") })
    }
}
