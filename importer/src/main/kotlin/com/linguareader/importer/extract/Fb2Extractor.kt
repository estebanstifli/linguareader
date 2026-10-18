package com.linguareader.importer.extract

import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedBlock
import com.linguareader.importer.ExtractedChapter
import com.linguareader.importer.ExtractedDocument
import com.linguareader.importer.ExtractedImage
import com.linguareader.importer.ImportException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.util.Base64

/**
 * FictionBook 2 (.fb2 y .fb2.zip), muy común en libros rusos y de Europa del Este. Es XML con
 * secciones anidadas; las imágenes van en `<binary>` como base64. Las notas al pie
 * (`<body name="notes">`) y sus llamadas no se leen.
 */
class Fb2Extractor : DocumentExtractor {
    override val id = "fb2"
    override val extensions = setOf("fb2", "fbz")

    override fun sniff(source: DocumentSource): Boolean {
        if (ZipArchive.isZip(source.head)) {
            val name = source.displayName.orEmpty().lowercase()
            return name.endsWith(".fb2.zip") || name.endsWith(".fbz")
        }
        return "<FictionBook" in String(source.head, Charsets.ISO_8859_1)
    }

    override fun extract(source: DocumentSource): ExtractedDocument {
        val bytes = if (ZipArchive.isZip(source.head)) {
            ZipArchive(source.file).use { zip ->
                val entry = zip.names.firstOrNull { it.lowercase().endsWith(".fb2") }
                    ?: throw ImportException(ImportException.Reason.CORRUPT, "El ZIP no contiene ningún .fb2")
                zip.bytes(entry)!!
            }
        } else {
            source.file.readBytes()
        }
        // charset null: jsoup respeta el BOM y la declaración <?xml encoding="windows-1251"?>.
        val document = Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
        val root = document.byLocalName("FictionBook").firstOrNull()
            ?: throw ImportException(ImportException.Reason.CORRUPT, "No es un FictionBook válido")

        val binaries = root.byLocalName("binary").associate { it.attr("id") to it }
        fun image(element: Element): ExtractedImage? {
            val href = element.attributes().firstOrNull { it.key.endsWith("href") }?.value ?: return null
            val binary = binaries[href.removePrefix("#")] ?: return null
            return runCatching { ExtractedImage(Base64.getMimeDecoder().decode(binary.text()), binary.attr("content-type")) }.getOrNull()
        }

        val titleInfo = root.byLocalName("title-info").firstOrNull()
        val authors = titleInfo?.byLocalName("author").orEmpty().mapNotNull { author ->
            listOf("first-name", "middle-name", "last-name")
                .mapNotNull { part -> author.byLocalName(part).firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() } }
                .joinToString(" ")
                .ifEmpty { author.byLocalName("nickname").firstOrNull()?.text()?.trim() }
                ?.takeIf { it.isNotEmpty() }
        }

        val builder = ChapterBuilder(::image)
        root.children()
            .filter { it.tagName().substringAfter(':') == "body" && it.attr("name") !in setOf("notes", "comments") }
            .forEach(builder::body)

        return ExtractedDocument(
            format = id,
            title = titleInfo?.byLocalName("book-title")?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: source.titleFromName,
            authors = authors,
            language = titleInfo?.byLocalName("lang")?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() },
            chapters = builder.finish(),
            cover = titleInfo?.byLocalName("coverpage")?.firstOrNull()?.byLocalName("image")?.firstOrNull()?.let(::image),
        )
    }

    private class ChapterBuilder(private val image: (Element) -> ExtractedImage?) {
        private val chapters = mutableListOf<ExtractedChapter>()
        private var title: String? = null
        private var blocks = mutableListOf<ExtractedBlock>()

        fun body(body: Element) = body.children().forEach(::element)

        private fun element(element: Element) {
            when (element.tagName().substringAfter(':')) {
                "section" -> {
                    element.children().firstOrNull { it.tagName().substringAfter(':') == "title" }?.let { heading ->
                        val text = heading.children().joinToString(". ") { inlineText(it) }.trim().trimEnd('.')
                        if (text.isNotEmpty()) newChapter(text)
                    }
                    element.children().filterNot { it.tagName().substringAfter(':') == "title" }.forEach(::element)
                }
                "title" -> Unit // título de <body>: repite el del libro
                "p", "subtitle", "v", "text-author" -> paragraph(inlineText(element))
                "image" -> image(element)?.let { blocks += ExtractedBlock.Image(it, element.attr("alt").ifBlank { null }) }
                "poem", "stanza", "cite", "epigraph", "annotation" -> element.children().forEach(::element)
                "table" -> element.children().forEach { row -> paragraph(inlineText(row)) }
                else -> Unit // empty-line y desconocidos
            }
        }

        private fun newChapter(text: String) {
            if (blocks.isEmpty()) {
                title = title?.let { "$it. $text" } ?: text
            } else {
                chapters += ExtractedChapter(title, blocks)
                blocks = mutableListOf()
                title = text
            }
        }

        private fun paragraph(text: String) {
            if (text.any { it.isLetterOrDigit() }) blocks += ExtractedBlock.Paragraph(text)
        }

        fun finish(): List<ExtractedChapter> {
            if (blocks.isNotEmpty()) chapters += ExtractedChapter(title, blocks)
            return chapters
        }

        /** Texto en línea sin llamadas a nota (`<a type="note">`). */
        private fun inlineText(element: Element): String {
            val out = StringBuilder()
            fun visit(node: Node) {
                when (node) {
                    is TextNode -> out.append(node.wholeText)
                    is Element -> if (!(node.tagName().substringAfter(':') == "a" && node.attr("type") == "note")) {
                        node.childNodes().forEach(::visit)
                        if (node.tagName().substringAfter(':') == "p") out.append(' ')
                    }
                }
            }
            visit(element)
            return HtmlContentWalker.clean(out.toString())
        }
    }
}
