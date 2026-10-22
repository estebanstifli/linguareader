package com.linguareader.importer

import com.linguareader.importer.extract.EpubExtractor
import com.linguareader.importer.extract.Fb2Extractor
import com.linguareader.importer.extract.HtmlExtractor
import com.linguareader.importer.extract.MarkdownExtractor
import com.linguareader.importer.extract.SubtitleExtractor
import com.linguareader.importer.extract.TextExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

class ExtractorsTest {

    @get:Rule val folder = TemporaryFolder()

    private val registry = ExtractorRegistry.default()

    private fun file(name: String, bytes: ByteArray): File = folder.newFile(name).apply { writeBytes(bytes) }

    private fun paragraphs(doc: ExtractedDocument) =
        doc.chapters.flatMap { c -> c.blocks.filterIsInstance<ExtractedBlock.Paragraph>().map { it.text } }

    @Test
    fun `txt de Gutenberg con metadatos, recorte de licencia, líneas cortadas y capítulos`() {
        val text = """
            The Project Gutenberg eBook of A Test

            Title: A Test Story
            Author: Jane Doe
            Language: English

            *** START OF THE PROJECT GUTENBERG EBOOK A TEST ***

            CHAPTER I

            It was a dark and stormy night; the rain fell in tor-
            rents, except at occasional intervals.

            Nobody came.

            CHAPTER II

            Morning arrived.

            *** END OF THE PROJECT GUTENBERG EBOOK A TEST ***

            License text that must not be read.
        """.trimIndent()
        val doc = registry.extract(DocumentSource(file("a.txt", text.toByteArray()), "a.txt"))
        assertEquals("txt", doc.format)
        assertEquals("A Test Story", doc.title)
        assertEquals(listOf("Jane Doe"), doc.authors)
        assertEquals("en", doc.language)
        assertEquals(listOf("CHAPTER I", "CHAPTER II"), doc.chapters.map { it.title })
        assertEquals(
            listOf(
                "It was a dark and stormy night; the rain fell in torrents, except at occasional intervals.",
                "Nobody came.",
                "Morning arrived.",
            ),
            paragraphs(doc),
        )
    }

    @Test
    fun `txt en windows-1252 conserva diacríticos`() {
        val bytes = "Él caminó hacia el café.\nLa niña sonrió.".toByteArray(Charset.forName("windows-1252"))
        val doc = TextExtractor().extract(DocumentSource(file("legacy.txt", bytes), "legacy.txt"))
        assertEquals(listOf("Él caminó hacia el café.", "La niña sonrió."), paragraphs(doc))
        assertEquals("legacy", doc.title)
    }

    @Test
    fun `txt con BOM UTF-16`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Bonjour à tous.".toByteArray(Charsets.UTF_16LE)
        val doc = registry.extract(DocumentSource(file("u16.txt", bytes), "u16.txt"))
        assertEquals(listOf("Bonjour à tous."), paragraphs(doc))
    }

    @Test
    fun `markdown quita marcas y usa encabezados como capítulos`() {
        val md = """
            ---
            title: Notas de viaje
            lang: es
            ---
            # Día 1

            Llegamos a **Lyon** con [mi hermana](https://example.com).
            Hacía _mucho_ calor.

            ```
            codigo()
            ```

            - Primer recuerdo
            - Segundo recuerdo

            ## Día 2

            ![foto](x.png)
            Volvimos.
        """.trimIndent()
        val doc = MarkdownExtractor().parse(md, "fallback")
        assertEquals("Notas de viaje", doc.title)
        assertEquals("es", doc.language)
        assertEquals(listOf("Día 1", "Día 2"), doc.chapters.map { it.title })
        assertEquals(
            listOf("Llegamos a Lyon con mi hermana. Hacía mucho calor.", "Primer recuerdo", "Segundo recuerdo", "Volvimos."),
            paragraphs(doc),
        )
    }

    @Test
    fun `html detectado por contenido aunque no tenga extensión`() {
        val html = """
            <!DOCTYPE html><html lang="fr"><head><title>Le phare</title><meta charset="utf-8"><script>var x=1;</script></head>
            <body><nav>Menu Accueil</nav><h1>Le phare</h1><p>Il était une fois un gardien.</p><p style="display:none">caché</p>
            <ul><li>Premier point</li></ul></body></html>
        """.trimIndent()
        val doc = registry.extract(DocumentSource(file("download", html.toByteArray()), "download"))
        assertEquals("html", doc.format)
        assertEquals("Le phare", doc.title)
        assertEquals("fr", doc.language)
        assertEquals(listOf("Il était une fois un gardien.", "Premier point"), paragraphs(doc))
    }

    @Test
    fun `marcado de Project Gutenberg sin licencia, con letra capital y pies de ilustración`() {
        val png = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(Fixtures.PNG)
        val html = """
            <html><body>
              <header class="pg-boilerplate pgheader" id="pg-header"><h2>The Project Gutenberg eBook of X</h2><p>This eBook is for the use of anyone.</p></header>
              <h2><img alt="" src="$png"/><span class="caption">I hope Mr. Bingley will like it.</span><br/>CHAPTER II.</h2>
              <p class="nind"><span class="letra"><img alt="M" src="$png"/></span>R. BENNET was among the earliest.</p>
              <div class="figcenter"><img alt="" src="$png"/><div class="caption"><p>Mr. and Mrs. Bennet</p></div></div>
              <p><span class="x-ebookmaker-pageno" title="{6}"><a id="page_6"></a></span></p>
              <p>“I hope Mr. Bingley will like it, Lizzy.”</p>
              <footer class="pg-boilerplate" id="pg-footer"><p>*** END OF THE PROJECT GUTENBERG EBOOK ***</p></footer>
            </body></html>
        """.trimIndent()
        val doc = HtmlExtractor().fromDocument(com.fleeksoft.ksoup.Ksoup.parse(html), null)
        assertEquals(listOf("CHAPTER II."), doc.chapters.map { it.title })
        assertEquals(listOf("MR. BENNET was among the earliest.", "“I hope Mr. Bingley will like it, Lizzy.”"), paragraphs(doc))
        val images = doc.chapters.single().blocks.filterIsInstance<ExtractedBlock.Image>()
        assertEquals(listOf(null, "Mr. and Mrs. Bennet"), images.map { it.alt })
    }

    @Test
    fun `página web lee solo el contenido principal`() {
        val longText = "La ciudad despertó despacio aquella mañana de otoño. ".repeat(6)
        val html = """
            <html lang="es"><head><meta property="og:title" content="Crónica de otoño"><title>Crónica | Diario</title></head><body>
              <header><a href="/">Diario</a><nav>Portada Deportes Cultura</nav></header>
              <div class="layout">
                <div class="content">
                  <h1>Crónica de otoño</h1>
                  <p>$longText</p>
                  <p>Los cafés abrieron sus puertas.</p>
                  <div class="share">Compartir en redes</div>
                </div>
                <aside><p>Lo más leído: otra noticia.</p></aside>
              </div>
              <footer><p>© Diario. Aviso legal.</p></footer>
              <div id="comments"><p>Primer comentario.</p></div>
            </body></html>
        """.trimIndent()
        val doc = HtmlExtractor().fromDocument(com.fleeksoft.ksoup.Ksoup.parse(html), null, webPage = true)
        assertEquals("Crónica de otoño", doc.title)
        assertEquals(listOf(longText.trim(), "Los cafés abrieron sus puertas."), paragraphs(doc))
    }

    @Test
    fun `epub 3 con nav, imágenes, notas y paginación`() {
        val source = DocumentSource(Fixtures.epub(folder), "book.epub")
        assertTrue(EpubExtractor().sniff(source))
        val doc = registry.extract(source)
        assertEquals("epub", doc.format)
        assertEquals("The Lighthouse Keeper", doc.title)
        assertEquals(listOf("Ana Test"), doc.authors)
        assertEquals("en-GB", doc.language)
        assertArrayEquals(Fixtures.PNG, doc.cover!!.bytes)
        // El título del índice y el h1 coinciden en "The Storm": no se duplica.
        assertEquals(listOf("One: The Storm", "Two: Morning"), doc.chapters.map { it.title })
        assertEquals(
            listOf(
                "The wind was strong. Mr. Gray closed the door.",
                "He lit the lamp, and the light went out over the sea.",
                "In the morning the sea was calm. It was a new day.",
            ),
            paragraphs(doc),
        )
        val image = doc.chapters[0].blocks[1] as ExtractedBlock.Image
        assertEquals("A lighthouse", image.alt)
    }

    @Test
    fun `epub 2 usa el NCX`() {
        val doc = registry.extract(DocumentSource(Fixtures.epub(folder, epub3 = false), "book.epub"))
        assertEquals("Chapter One. The Storm", doc.chapters[0].title)
        assertEquals("Chapter Two", doc.chapters[1].title)
    }

    @Test
    fun `epub con DRM se rechaza con motivo claro`() {
        try {
            registry.extract(DocumentSource(Fixtures.epub(folder, drm = true), "drm.epub"))
            fail("Debía rechazar el DRM")
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.DRM_PROTECTED, e.reason)
        }
    }

    @Test
    fun `fb2 en windows-1251 con notas, poemas, binarios y fb2 zip`() {
        val xml = Fixtures.fb2()
        val plain = DocumentSource(file("libro.fb2", xml.toByteArray()), "libro.fb2")
        assertTrue(Fb2Extractor().sniff(plain))
        val doc = registry.extract(plain)
        assertEquals("El faro", doc.title)
        assertEquals(listOf("Lucía Pérez"), doc.authors)
        assertEquals("es", doc.language)
        assertNotNull(doc.cover)
        assertEquals(listOf("Capítulo 1. La tormenta", "Capítulo 2"), doc.chapters.map { it.title })
        val text = paragraphs(doc)
        assertEquals("El viento soplaba con fuerza. Nadie salió.", text[0])
        assertTrue("Una nota que no se lee." !in text)
        assertTrue("Mar en calma," in text)

        val cyrillic = Fixtures.fb2("windows-1251").replace("El faro</book-title>", "Маяк</book-title>")
        val legacy = registry.extract(DocumentSource(file("ru.fb2", cyrillic.toByteArray(Charset.forName("windows-1251"))), "ru.fb2"))
        assertEquals("Маяк", legacy.title)

        val zipped = Fixtures.zip(folder, "libro.fb2.zip", listOf("libro.fb2" to xml.toByteArray()), storedFirst = false)
        assertEquals("El faro", registry.extract(DocumentSource(zipped, "libro.fb2.zip")).title)
    }

    @Test
    fun `srt y vtt agrupan cues sin tiempos ni etiquetas`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n<i>Hello there.</i>\n\n2\n00:00:03,000 --> 00:00:04,000\n- How are you?\n- Fine.\n"
        val source = DocumentSource(file("movie.srt", srt.toByteArray()), "movie.srt")
        assertTrue(SubtitleExtractor().sniff(source))
        assertEquals(listOf("Hello there. How are you? Fine."), paragraphs(registry.extract(source)))

        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000\nBonjour.\n\nNOTE comentario\n\n00:03.000 --> 00:04.000\n{\\an8}Salut.\n"
        assertEquals(listOf("Bonjour. Salut."), paragraphs(registry.extract(DocumentSource(file("x.vtt", vtt.toByteArray()), "x.vtt"))))
    }

    @Test
    fun `formatos desconocidos o binarios se rechazan`() {
        val pdfLike = "%PDF-1.7\n".toByteArray() + ByteArray(100)
        try {
            registry.extract(DocumentSource(file("x.bin", pdfLike), "x.bin"))
            fail()
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.UNSUPPORTED_FORMAT, e.reason)
        }
        assertFalse(registry.supportedExtensions.isEmpty())
    }

    @Test
    fun `documento sin texto`() {
        try {
            registry.extract(DocumentSource(file("vacio.txt", "\n\n * * * \n".toByteArray()), "vacio.txt"))
            fail()
        } catch (e: ImportException) {
            assertEquals(ImportException.Reason.NO_TEXT, e.reason)
        }
    }
}
