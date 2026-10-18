package com.linguareader.importer.extract

import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedBlock
import com.linguareader.importer.ExtractedChapter
import com.linguareader.importer.ExtractedDocument
import com.linguareader.importer.ExtractedImage
import com.linguareader.importer.ImportException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * EPUB 2 y 3 sin DRM. Solo se necesita el texto en orden de lectura, así que basta con
 * `container.xml` → OPF (metadatos, manifest, spine) → índice (nav o NCX) → XHTML de cada
 * elemento del spine. No hace falta un motor de maquetado como Readium.
 */
class EpubExtractor : DocumentExtractor {
    override val id = "epub"
    override val extensions = setOf("epub")

    override fun sniff(source: DocumentSource): Boolean =
        ZipArchive.isZip(source.head) && String(source.head, 30, minOf(60, source.head.size - 30).coerceAtLeast(0), Charsets.US_ASCII)
            .contains("application/epub+zip")

    override fun extract(source: DocumentSource): ExtractedDocument = ZipArchive(source.file).use { zip ->
        rejectDrm(zip)
        val container = xml(zip.text("META-INF/container.xml") ?: corrupt("Falta META-INF/container.xml"))
        val opfPath = container.byLocalName("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
            ?: corrupt("container.xml no indica el paquete OPF")
        val opf = xml(zip.text(opfPath) ?: corrupt("Falta el paquete OPF: $opfPath"))

        val manifest = opf.byLocalName("item").associate { item ->
            item.attr("id") to ManifestItem(
                path = ZipArchive.resolve(opfPath, item.attr("href")),
                mediaType = item.attr("media-type"),
                properties = item.attr("properties").split(' ').toSet(),
            )
        }
        val spineElement = opf.byLocalName("spine").firstOrNull() ?: corrupt("El OPF no tiene spine")
        val spine = spineElement.byLocalName("itemref").mapNotNull { manifest[it.attr("idref")] }
            .filter { it.mediaType.contains("html") }
        val toc = tableOfContents(zip, manifest, spineElement.attr("toc"))

        val chapters = mutableListOf<ExtractedChapter>()
        for (item in spine) {
            val html = zip.text(item.path) ?: continue
            val document = Jsoup.parse(html, "")
            val walker = HtmlContentWalker(resolveImage = { src -> image(zip, ZipArchive.resolve(item.path, src)) })
            chapters += walker.walk(document.body(), toc[item.path])
        }

        val metadata = opf.byLocalName("metadata").firstOrNull()
        fun meta(name: String) = metadata?.byLocalName(name)?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val coverId = metadata?.byLocalName("meta")?.firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val coverItem = manifest.values.firstOrNull { "cover-image" in it.properties } ?: coverId?.let(manifest::get)
        val cover = coverItem?.takeIf { it.mediaType.startsWith("image/") }?.let { image(zip, it.path) }

        ExtractedDocument(
            format = id,
            title = meta("title").firstOrNull() ?: source.titleFromName,
            authors = meta("creator"),
            language = meta("language").firstOrNull(),
            chapters = withoutFillerPages(chapters, cover),
            cover = cover,
        )
    }

    /**
     * Quita las páginas sin texto real (la que envuelve la portada, un enlace «back», láminas): sus
     * ilustraciones pasan al capítulo siguiente, salvo las que repiten la portada.
     */
    private fun withoutFillerPages(chapters: List<ExtractedChapter>, cover: ExtractedImage?): List<ExtractedChapter> {
        val result = mutableListOf<ExtractedChapter>()
        var carried = emptyList<ExtractedBlock>()
        for (chapter in chapters) {
            val blocks = chapter.blocks.filterNot { block ->
                block is ExtractedBlock.Image && cover != null && block.image.bytes.contentEquals(cover.bytes)
            }
            val words = blocks.filterIsInstance<ExtractedBlock.Paragraph>().sumOf { it.text.split(' ').size }
            if (words < MIN_WORDS) {
                carried = carried + blocks.filterIsInstance<ExtractedBlock.Image>()
                continue
            }
            result += chapter.copy(blocks = carried + blocks)
            carried = emptyList()
        }
        if (carried.isNotEmpty() && result.isNotEmpty()) {
            result[result.lastIndex] = result.last().let { it.copy(blocks = it.blocks + carried) }
        }
        return result
    }

    private data class ManifestItem(val path: String, val mediaType: String, val properties: Set<String>)

    /** Ruta del XHTML → título del primer punto del índice que apunta a él. */
    private fun tableOfContents(zip: ZipArchive, manifest: Map<String, ManifestItem>, ncxId: String): Map<String, String> {
        val toc = linkedMapOf<String, String>()
        val nav = manifest.values.firstOrNull { "nav" in it.properties }
        if (nav != null) {
            val document = Jsoup.parse(zip.text(nav.path) ?: "", "")
            val tocNav = document.select("nav").firstOrNull { it.attr("epub:type") == "toc" || it.attr("role") == "doc-toc" }
                ?: document.selectFirst("nav")
            tocNav?.select("a[href]")?.forEach { a ->
                toc.putIfAbsent(ZipArchive.resolve(nav.path, a.attr("href")), HtmlContentWalker.clean(a.text()))
            }
        }
        if (toc.isEmpty()) {
            val ncx = manifest[ncxId] ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            if (ncx != null) {
                xml(zip.text(ncx.path) ?: "").byLocalName("navPoint").forEach { point ->
                    val label = point.byLocalName("text").firstOrNull()?.text()?.trim().orEmpty()
                    val src = point.byLocalName("content").firstOrNull()?.attr("src").orEmpty()
                    if (label.isNotEmpty() && src.isNotEmpty()) toc.putIfAbsent(ZipArchive.resolve(ncx.path, src), label)
                }
            }
        }
        return toc.filterValues { it.isNotEmpty() }
    }

    private fun image(zip: ZipArchive, path: String): ExtractedImage? =
        zip.bytes(path, limit = MAX_IMAGE_BYTES)?.let { ExtractedImage(it) }

    /**
     * `encryption.xml` también se usa para ofuscar fuentes (legítimo y sin efecto en el texto);
     * cualquier otro recurso cifrado, o `rights.xml` de Adobe, implica DRM.
     */
    private fun rejectDrm(zip: ZipArchive) {
        if (zip.has("META-INF/rights.xml")) drm()
        val encryption = zip.text("META-INF/encryption.xml") ?: return
        val encrypted = xml(encryption).byLocalName("EncryptedData")
        val protectedContent = encrypted.any { data ->
            val algorithm = data.byLocalName("EncryptionMethod").firstOrNull()?.attr("Algorithm").orEmpty()
            val uri = data.byLocalName("CipherReference").firstOrNull()?.attr("URI").orEmpty().lowercase()
            algorithm !in FONT_OBFUSCATION && !FONT_FILE.containsMatchIn(uri)
        }
        if (protectedContent) drm()
    }

    private fun drm(): Nothing = throw ImportException(
        ImportException.Reason.DRM_PROTECTED,
        "Este EPUB está protegido con DRM. Solo se pueden importar libros sin DRM.",
    )

    private fun corrupt(message: String): Nothing = throw ImportException(ImportException.Reason.CORRUPT, message)

    companion object {
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        private const val MIN_WORDS = 3
        private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
        private val FONT_FILE = Regex("\\.(otf|ttf|woff2?)$")

        internal fun xml(text: String): Document = Jsoup.parse(text, "", Parser.xmlParser())
    }
}

/** Elementos por nombre local, ignorando prefijo de espacio de nombres y mayúsculas ("dc:title" ≈ "title"). */
internal fun Element.byLocalName(name: String): List<Element> =
    getAllElements().filter { it.tagName().substringAfter(':').equals(name, ignoreCase = true) }
