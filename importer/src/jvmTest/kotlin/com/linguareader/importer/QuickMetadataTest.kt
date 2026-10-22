package com.linguareader.importer

import okio.Path.Companion.toOkioPath
import com.linguareader.importer.extract.QuickMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuickMetadataTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `epub metadata comes from the OPF`() {
        val meta = QuickMetadata.read(Fixtures.epub(folder).toOkioPath())
        assertEquals("The Lighthouse Keeper", meta.title)
        assertEquals(listOf("Ana Test"), meta.authors)
        assertEquals("en-GB", meta.language)
    }

    @Test
    fun `fb2 header is read even when the book is cut off`() {
        val head = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook><description><title-info>
              <author><first-name>Lev</first-name><last-name>Tolstói</last-name></author>
              <book-title>Anna Karénina &amp; otros</book-title>
              <lang>es</lang>
            </title-info></description><body><p>Todas las familias felices se parecen
        """.trimIndent()
        val file = folder.newFile("anna.fb2").apply { writeText(head) }
        val meta = QuickMetadata.read(file.toOkioPath())
        assertEquals("Anna Karénina & otros", meta.title)
        assertEquals(listOf("Lev Tolstói"), meta.authors)
        assertEquals("es", meta.language)
    }

    @Test
    fun `other formats and damaged files fall back to the file name`() {
        val txt = folder.newFile("El_viaje   de Ana.txt").apply { writeText("Hola.") }
        assertEquals("El viaje de Ana", QuickMetadata.read(txt.toOkioPath()).title)
        val broken = folder.newFile("roto.epub").apply { writeText("no es un zip") }
        val meta = QuickMetadata.read(broken.toOkioPath())
        assertEquals("roto", meta.title)
        assertNull(meta.language)
    }
}
