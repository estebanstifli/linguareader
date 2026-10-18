package com.linguareader.importer

import com.linguareader.importer.extract.RtfExtractor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfficeExtractorsTest {

    // Escapes RTF "\uNNNN" construidos por partes: algunos editores los convierten en el carácter.
    private val BACKSLASH = "\\"

    @get:Rule val folder = TemporaryFolder()

    private val registry = ExtractorRegistry.default()

    private fun paragraphs(doc: ExtractedDocument) =
        doc.chapters.flatMap { c -> c.blocks.filterIsInstance<ExtractedBlock.Paragraph>().map { it.text } }

    @Test
    fun `docx con estilos de título localizados, control de cambios, tablas e imágenes`() {
        val document = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Ttulo1"/></w:pPr><w:r><w:t>La llegada</w:t></w:r></w:p>
                <w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">Ana llegó </w:t></w:r><w:r><w:t>a Sevilla.</w:t></w:r>
                  <w:del><w:r><w:delText>texto borrado</w:delText></w:r></w:del><w:r><w:tab/><w:t>Hacía calor.</w:t></w:r>
                  <w:r><w:footnoteReference w:id="1"/></w:r></w:p>
                <w:p><w:r><w:drawing><a:blip r:embed="rId5"/></w:drawing></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Celda uno.</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                <w:p><w:pPr><w:pStyle w:val="Ttulo1"/></w:pPr><w:r><w:t>La partida</w:t></w:r></w:p>
                <w:p><w:r><w:t>Se fue al amanecer.</w:t></w:r></w:p>
                <w:sectPr/>
              </w:body>
            </w:document>
        """.trimIndent()
        val styles = """
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:docDefaults><w:rPrDefault><w:rPr><w:lang w:val="es-ES"/></w:rPr></w:rPrDefault></w:docDefaults>
              <w:style w:type="paragraph" w:styleId="Ttulo1"><w:name w:val="heading 1"/></w:style>
            </w:styles>
        """.trimIndent()
        val rels = """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId5" Type="image" Target="media/image1.png"/>
            </Relationships>
        """.trimIndent()
        val core = """
            <cp:coreProperties xmlns:cp="x" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title>Viaje al sur</dc:title><dc:creator>Marta Ruiz</dc:creator>
            </cp:coreProperties>
        """.trimIndent()
        val file = Fixtures.zip(
            folder, "viaje.docx",
            listOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "word/document.xml" to document.toByteArray(),
                "word/styles.xml" to styles.toByteArray(),
                "word/_rels/document.xml.rels" to rels.toByteArray(),
                "word/media/image1.png" to Fixtures.PNG,
                "docProps/core.xml" to core.toByteArray(),
            ),
            storedFirst = false,
        )
        val doc = registry.extract(DocumentSource(file, "viaje.docx"))
        assertEquals("docx", doc.format)
        assertEquals("Viaje al sur", doc.title)
        assertEquals(listOf("Marta Ruiz"), doc.authors)
        assertEquals("es-ES", doc.language)
        assertEquals(listOf("La llegada", "La partida"), doc.chapters.map { it.title })
        assertEquals(listOf("Ana llegó a Sevilla. Hacía calor.", "Celda uno.", "Se fue al amanecer."), paragraphs(doc))
        assertEquals(1, doc.chapters[0].blocks.count { it is ExtractedBlock.Image })
    }

    @Test
    fun `odt con encabezados, listas, espacios y notas`() {
        val content = """
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
                xmlns:xlink="http://www.w3.org/1999/xlink">
              <office:body><office:text>
                <text:h text:outline-level="1">Premier jour</text:h>
                <text:p>Il fait beau<text:note><text:note-body><text:p>note</text:p></text:note-body></text:note>.<text:s text:c="2"/>On sort.</text:p>
                <text:list><text:list-item><text:p>Pain</text:p></text:list-item></text:list>
                <text:p><draw:frame><draw:image xlink:href="Pictures/a.png"/></draw:frame></text:p>
                <text:h text:outline-level="1">Deuxième jour</text:h>
                <text:p>Il pleut.</text:p>
              </office:text></office:body>
            </office:document-content>
        """.trimIndent()
        val meta = """
            <office:document-meta xmlns:office="o" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:meta="m">
              <office:meta><dc:title>Journal</dc:title><meta:initial-creator>Paul</meta:initial-creator><dc:language>fr-FR</dc:language></office:meta>
            </office:document-meta>
        """.trimIndent()
        val file = Fixtures.zip(
            folder, "journal.odt",
            listOf(
                "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(),
                "content.xml" to content.toByteArray(),
                "meta.xml" to meta.toByteArray(),
                "Pictures/a.png" to Fixtures.PNG,
            ),
        )
        // Sin extensión: se reconoce por el mimetype del ZIP.
        val doc = registry.extract(DocumentSource(file, "journal"))
        assertEquals("odt", doc.format)
        assertEquals("Journal", doc.title)
        assertEquals(listOf("Paul"), doc.authors)
        assertEquals("fr-FR", doc.language)
        assertEquals(listOf("Premier jour", "Deuxième jour"), doc.chapters.map { it.title })
        assertEquals(listOf("Il fait beau. On sort.", "Pain", "Il pleut."), paragraphs(doc))
        assertEquals(1, doc.chapters[0].blocks.count { it is ExtractedBlock.Image })
    }

    @Test
    fun `rtf con codepage, unicode, destinos ignorados y metadatos`() {
        val rtf = """{\rtf1\ansi\ansicpg1252\deff0{\fonttbl{\f0 Times New Roman;}}{\colortbl;\red0\green0\blue0;}""" +
            """{\info{\title El r\'edo}{\author Jos\'e9}}{\*\generator Word;}""" +
            """\pard Cap\'edtulo 1\par""" +
            """\pard El ni\'f1o dijo: \ldblquote hola\rdblquote .{\footnote nota oculta}\par""" +
            """\pard\uc1 Caf\u233? y m\u225?s""" + BACKSLASH + """u8212? fin.\par}"""
        val file = File(folder.root, "r.rtf").apply { writeBytes(rtf.toByteArray(Charsets.ISO_8859_1)) }
        val doc = registry.extract(DocumentSource(file, "r.rtf"))
        assertEquals("rtf", doc.format)
        assertEquals("El río", doc.title)
        assertEquals(listOf("José"), doc.authors)
        assertEquals(listOf("Capítulo 1"), doc.chapters.map { it.title })
        assertEquals(listOf("El niño dijo: “hola”.", "Café y más— fin."), paragraphs(doc))
    }

    @Test
    fun `rtf salta imágenes binarias y grupos con asterisco`() {
        val parsed = RtfExtractor().parse("""{\rtf1{\*\shppict{\pict\pngblip 89504e47}}Hola{\*\unknowndest basura} mundo\par}""")
        assertEquals(listOf("Hola mundo"), parsed.paragraphs)
    }
}
