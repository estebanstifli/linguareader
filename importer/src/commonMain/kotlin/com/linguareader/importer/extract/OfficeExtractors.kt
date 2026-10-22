package com.linguareader.importer.extract

import com.linguareader.core.platform.decodeLatin1
import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedBlock
import com.linguareader.importer.ExtractedChapter
import com.linguareader.importer.ExtractedDocument
import com.linguareader.importer.ExtractedImage
import com.linguareader.importer.ImportException
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/** Acumula capítulos a partir de títulos y párrafos, uniendo títulos consecutivos sin texto. */
internal class ChapterAccumulator {
    private val chapters = mutableListOf<ExtractedChapter>()
    private var title: String? = null
    private var blocks = mutableListOf<ExtractedBlock>()

    fun heading(text: String) {
        val clean = HtmlContentWalker.clean(text)
        if (clean.isEmpty()) return
        if (blocks.isEmpty()) {
            title = title?.let { "$it. $clean" } ?: clean
        } else {
            chapters += ExtractedChapter(title, blocks)
            blocks = mutableListOf()
            title = clean
        }
    }

    fun paragraph(text: String) {
        val clean = HtmlContentWalker.clean(text)
        if (clean.any { it.isLetterOrDigit() }) blocks += ExtractedBlock.Paragraph(clean)
    }

    fun image(image: ExtractedImage?, alt: String? = null) {
        if (image != null && ImageTypes.extension(image.bytes) != null) blocks += ExtractedBlock.Image(image, alt)
    }

    fun finish(): List<ExtractedChapter> {
        if (blocks.isNotEmpty()) chapters += ExtractedChapter(title, blocks)
        blocks = mutableListOf()
        return chapters.toList()
    }
}

private fun Element.local(): String = tagName().substringAfter(':')

private fun Element.attrLocal(name: String): String =
    attributes().firstOrNull { it.key.substringAfter(':') == name }?.value.orEmpty()

private fun Element.childrenLocal(name: String): List<Element> = children().filter { it.local() == name }

/**
 * Word 2007+ (.docx): ZIP con `word/document.xml`. Los títulos se reconocen por el estilo
 * ("heading 1", "Title"...) o por su nivel de esquema, sin depender del idioma de Word
 * (el id de estilo cambia: "Heading1", "Ttulo1"...). Se ignora el texto borrado con control de cambios.
 */
class DocxExtractor : DocumentExtractor {
    override val id = "docx"
    override val extensions = setOf("docx")

    override fun sniff(source: DocumentSource): Boolean =
        ZipArchive.isZip(source.head) && ZipArchive(source.fileSystem, source.path).use { it.has(DOCUMENT) }

    override fun extract(source: DocumentSource): ExtractedDocument = ZipArchive(source.fileSystem, source.path).use { zip ->
        val document = EpubExtractor.xml(zip.text(DOCUMENT) ?: throw ImportException(ImportException.Reason.CORRUPT, "Falta $DOCUMENT"))
        val headingStyles = headingStyles(zip.text("word/styles.xml"))
        val relationships = zip.text("word/_rels/document.xml.rels")?.let(EpubExtractor::xml)
            ?.byLocalName("Relationship")?.associate { it.attr("Id") to it.attr("Target") }.orEmpty()

        val out = ChapterAccumulator()
        fun paragraph(p: Element) {
            val style = p.byLocalName("pStyle").firstOrNull()?.attrLocal("val")
            val outline = p.byLocalName("outlineLvl").firstOrNull()?.attrLocal("val")?.toIntOrNull()
            p.byLocalName("blip").forEach { blip ->
                val target = relationships[blip.attrLocal("embed")] ?: return@forEach
                out.image(zip.bytes(ZipArchive.resolve(DOCUMENT, target))?.let { ExtractedImage(it) })
            }
            val text = runText(p)
            if (style in headingStyles || (outline != null && outline <= 1)) out.heading(text) else out.paragraph(text)
        }
        val body = document.byLocalName("body").firstOrNull() ?: throw ImportException(ImportException.Reason.CORRUPT, "Documento sin cuerpo")
        body.children().forEach { block ->
            when (block.local()) {
                "p" -> paragraph(block)
                "tbl" -> block.byLocalName("tc").forEach { cell -> cell.childrenLocal("p").forEach(::paragraph) }
                "sdt" -> block.byLocalName("p").forEach(::paragraph)
            }
        }

        val core = zip.text("docProps/core.xml")?.let(EpubExtractor::xml)
        fun meta(name: String) = core?.byLocalName(name)?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val defaultLanguage = zip.text("word/styles.xml")?.let(EpubExtractor::xml)
            ?.byLocalName("docDefaults")?.firstOrNull()?.byLocalName("lang")?.firstOrNull()?.attrLocal("val")
        ExtractedDocument(
            format = id,
            title = meta("title") ?: source.titleFromName,
            authors = listOfNotNull(meta("creator")),
            language = meta("language") ?: defaultLanguage?.takeIf { it.isNotBlank() },
            chapters = out.finish(),
            cover = thumbnail(zip),
        )
    }

    /**
     * Vista previa de la primera página que Word guarda si se marcó «Guardar miniatura»
     * (relación `…/metadata/thumbnail` del paquete). Solo JPEG/PNG: los EMF/WMF no se decodifican.
     */
    private fun thumbnail(zip: ZipArchive): ExtractedImage? {
        val target = zip.text("_rels/.rels")?.let(EpubExtractor::xml)?.byLocalName("Relationship")
            ?.firstOrNull { it.attr("Type").endsWith("/metadata/thumbnail") }?.attr("Target")
            ?: return null
        val bytes = zip.bytes(target.removePrefix("/")) ?: return null
        return ExtractedImage(bytes).takeIf { ImageTypes.extension(bytes) in setOf("jpg", "png") }
    }

    /** Ids de estilo que son título de nivel 1–2 (por nombre canónico o nivel de esquema). */
    private fun headingStyles(stylesXml: String?): Set<String> {
        val styles = stylesXml?.let(EpubExtractor::xml)?.byLocalName("style").orEmpty()
        return styles.filter { style ->
            val name = style.childrenLocal("name").firstOrNull()?.attrLocal("val")?.lowercase().orEmpty()
            val outline = style.byLocalName("outlineLvl").firstOrNull()?.attrLocal("val")?.toIntOrNull()
            name in setOf("title", "heading 1", "heading 2") || (outline != null && outline <= 1)
        }.map { it.attrLocal("styleId") }.toSet() + setOf("Title", "Heading1", "Heading2")
    }

    /** Texto de un párrafo: `w:t`, tabuladores y saltos; sin texto borrado ni referencias a notas. */
    private fun runText(p: Element): String {
        val out = StringBuilder()
        fun visit(node: Node) {
            if (node is TextNode) return // espacios de formato del XML
            val element = node as? Element ?: return
            when (element.local()) {
                "t" -> out.append(element.wholeText())
                "tab", "br", "cr" -> out.append(' ')
                "noBreakHyphen" -> out.append('-')
                "del", "delText", "instrText", "footnoteReference", "endnoteReference", "rPr", "pPr" -> Unit
                else -> element.childNodes().forEach(::visit)
            }
        }
        visit(p)
        return out.toString()
    }

    private companion object {
        const val DOCUMENT = "word/document.xml"
    }
}

/** OpenDocument Text (.odt, LibreOffice): ZIP con `content.xml` y `meta.xml`. */
class OdtExtractor : DocumentExtractor {
    override val id = "odt"
    override val extensions = setOf("odt")

    override fun sniff(source: DocumentSource): Boolean =
        ZipArchive.isZip(source.head) &&
            "application/vnd.oasis.opendocument.text" in source.head.decodeLatin1().take(120)

    override fun extract(source: DocumentSource): ExtractedDocument = ZipArchive(source.fileSystem, source.path).use { zip ->
        val content = EpubExtractor.xml(zip.text("content.xml") ?: throw ImportException(ImportException.Reason.CORRUPT, "Falta content.xml"))
        val text = content.byLocalName("text").firstOrNull { it.parent()?.local() == "body" }
            ?: throw ImportException(ImportException.Reason.CORRUPT, "El documento no tiene cuerpo de texto")

        val out = ChapterAccumulator()
        fun inline(element: Element): String {
            val sb = StringBuilder()
            fun visit(node: Node) {
                when (node) {
                    is TextNode -> sb.append(node.getWholeText())
                    is Element -> when (node.local()) {
                        "s" -> sb.append(" ".repeat(node.attrLocal("c").toIntOrNull() ?: 1))
                        "tab", "line-break" -> sb.append(' ')
                        "note", "annotation", "frame", "bookmark-ref" -> Unit
                        else -> node.childNodes().forEach(::visit)
                    }
                }
            }
            visit(element)
            return sb.toString()
        }
        fun block(element: Element) {
            when (element.local()) {
                "h" -> {
                    val level = element.attrLocal("outline-level").toIntOrNull() ?: 1
                    if (level <= 2) out.heading(inline(element)) else out.paragraph(inline(element))
                }
                "p" -> {
                    element.byLocalName("image").forEach { image ->
                        out.image(zip.bytes(ZipArchive.resolve("content.xml", image.attrLocal("href")))?.let { ExtractedImage(it) })
                    }
                    out.paragraph(inline(element))
                }
                "list", "list-item", "section", "table", "table-row", "table-cell", "table-header-rows", "table-rows" ->
                    element.children().forEach(::block)
            }
        }
        text.children().forEach(::block)

        val meta = zip.text("meta.xml")?.let(EpubExtractor::xml)
        fun meta(name: String) = meta?.byLocalName(name)?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
        ExtractedDocument(
            format = id,
            title = meta("title") ?: source.titleFromName,
            authors = listOfNotNull(meta("creator") ?: meta("initial-creator")),
            language = meta("language"),
            chapters = out.finish(),
            // LibreOffice guarda siempre una miniatura PNG de la primera página.
            cover = zip.bytes("Thumbnails/thumbnail.png")?.takeIf { ImageTypes.extension(it) == "png" }?.let { ExtractedImage(it) },
        )
    }
}
