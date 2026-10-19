package com.linguareader.importer

import com.linguareader.importer.extract.EpubExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Como los EPUB de Project Gutenberg: un capítulo largo queda partido entre dos archivos y el índice apunta a un
 * ancla a mitad del segundo. Lo que hay antes de ese ancla es el final del capítulo anterior.
 */
class EpubSplitChaptersTest {

    @get:Rule val folder = TemporaryFolder()

    private fun book(): ExtractedDocument {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Split</dc:title><dc:language>en</dc:language></metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                <item id="pic" href="pic.png" media-type="image/png"/>
                <item id="cap" href="cap.png" media-type="image/png"/>
              </manifest>
              <spine toc="ncx"><itemref idref="a"/><itemref idref="b"/></spine>
            </package>
        """.trimIndent()
        val ncx = """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
              <navPoint id="1"><navLabel><text>Chapter I.</text></navLabel><content src="a.xhtml#c1"/></navPoint>
              <navPoint id="2"><navLabel><text>Piling up the fire. CHAPTER II.</text></navLabel><content src="b.xhtml#c2"/></navPoint>
            </navMap></ncx>
        """.trimIndent()
        val a = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
              <h2 id="c1">Chapter I.</h2>
              <p>It is a truth universally acknowledged that the story begins here.</p>
            </body></html>
        """.trimIndent()
        val b = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
              <p>And the first chapter ends in the second file.</p>
              <h2 id="c2"><img src="pic.png" alt=""/><br/><span class="caption">Piling up the fire.</span><br/>CHAPTER II.</h2>
              <p><span class="letra"><img src="cap.png" alt="“I"/></span> hope, my dear, said Mr. Bennet.</p>
            </body></html>
        """.trimIndent()
        val file = Fixtures.zip(
            folder, "split.epub",
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to """
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                """.trimIndent().toByteArray(),
                "OEBPS/content.opf" to opf.toByteArray(),
                "OEBPS/toc.ncx" to ncx.toByteArray(),
                "OEBPS/a.xhtml" to a.toByteArray(),
                "OEBPS/b.xhtml" to b.toByteArray(),
                "OEBPS/pic.png" to Fixtures.PNG2,
                "OEBPS/cap.png" to Fixtures.PNG,
            ),
        )
        return EpubExtractor().extract(DocumentSource(file, "split.epub"))
    }

    @Test
    fun `the start of a file before its first heading continues the previous chapter`() {
        val doc = book()
        assertEquals(listOf("Chapter I.", "CHAPTER II."), doc.chapters.map { it.title })
        val first = doc.chapters[0].blocks.filterIsInstance<ExtractedBlock.Paragraph>().map { it.text }
        assertEquals("And the first chapter ends in the second file.", first.last())
    }

    @Test
    fun `a drop cap with punctuation in its alt text stays in the text`() {
        val second = book().chapters[1].blocks.filterIsInstance<ExtractedBlock.Paragraph>().first().text
        assertTrue(second, second.startsWith("“I hope"))
    }
}
