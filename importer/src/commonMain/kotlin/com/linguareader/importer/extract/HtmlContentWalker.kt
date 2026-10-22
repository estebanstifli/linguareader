package com.linguareader.importer.extract

import com.linguareader.importer.ExtractedBlock
import com.linguareader.importer.ExtractedChapter
import com.linguareader.importer.ExtractedImage
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Convierte el cuerpo de un documento HTML/XHTML en capítulos de párrafos e imágenes.
 *
 * - Cada elemento de bloque cierra el párrafo en curso; el texto en línea se acumula.
 * - `h1`–`h3` abren capítulo (o titulan el actual si aún está vacío).
 * - Se descartan scripts, estilos, navegación y llamadas a nota (`sup`), que leídas en voz alta molestan.
 * - [resolveImage] traduce el `src` de una imagen a bytes (en EPUB, una entrada del ZIP).
 */
class HtmlContentWalker(
    private val resolveImage: (src: String) -> ExtractedImage? = { null },
) {
    private val chapters = mutableListOf<ExtractedChapter>()
    private var title: String? = null
    private var blocks = mutableListOf<ExtractedBlock>()
    private val paragraph = StringBuilder()
    private var sawHeading = false

    /** Tras [walk]: hubo texto antes del primer encabezado (en EPUB, posible continuación del archivo anterior). */
    var textBeforeFirstHeading = false
        private set

    /** Recorre [root] y devuelve los capítulos; [initialTitle] titula el primero (p. ej. desde el índice del EPUB). */
    fun walk(root: Element, initialTitle: String? = null): List<ExtractedChapter> {
        chapters.clear()
        blocks = mutableListOf()
        paragraph.clear()
        sawHeading = false
        textBeforeFirstHeading = false
        title = initialTitle?.let(::clean)?.takeIf { it.isNotEmpty() }
        visit(root)
        flushParagraph()
        if (!sawHeading) textBeforeFirstHeading = blocks.any { it is ExtractedBlock.Paragraph }
        closeChapter()
        return chapters.toList()
    }

    private fun visit(node: Node) {
        when (node) {
            is TextNode -> paragraph.append(node.getWholeText())
            is Element -> visitElement(node)
        }
    }

    private fun visitElement(element: Element) {
        val tag = element.normalName().substringAfter(':')
        if (tag in SKIPPED || isHidden(element) || isBoilerplate(element)) return
        when {
            tag in HEADINGS -> {
                flushParagraph()
                // Los títulos ilustrados llevan la imagen y su pie dentro del h2: el pie no es título.
                heading(clean(element.clone().apply { select(CAPTIONS).remove() }.text()))
                element.select("img, image").forEach(::image)
                return
            }
            isCaption(element) -> {
                // Pie de ilustración: describe la imagen; no se lee en voz alta como texto del libro.
                flushParagraph()
                captionLastImage(clean(element.text()))
                return
            }
            tag == "br" -> {
                paragraph.append(' ')
                return
            }
            tag == "img" || tag == "image" -> {
                image(element)
                return
            }
        }
        val block = tag in BLOCKS
        if (block && tag !in CONTAINERS && isLinkList(element)) return // índice, menú: no se lee
        if (block) flushParagraph()
        element.childNodes().forEach(::visit)
        if (block) flushParagraph()
    }

    private fun heading(text: String) {
        if (text.isEmpty()) return
        if (!sawHeading) {
            sawHeading = true
            textBeforeFirstHeading = blocks.any { it is ExtractedBlock.Paragraph }
        }
        if (blocks.isEmpty()) {
            val current = title
            title = when {
                current == null -> text
                current.contains(text, ignoreCase = true) || text.contains(current, ignoreCase = true) ->
                    if (text.length > current.length) text else current
                current.length + text.length < 80 -> "$current. $text"
                else -> current
            }
        } else {
            closeChapter()
            title = text
        }
    }

    private fun image(element: Element) {
        // Letra capital dibujada («M» de «MR. BENNET», «“I» de «“I hope»): es texto, no ilustración.
        val alt = element.attr("alt").trim()
        if (alt.length in 1..4 && alt.any(Char::isLetter) && alt.all { it.isLetter() || it in DROP_CAP_PUNCTUATION }) {
            paragraph.append(alt)
            return
        }
        val src = element.attr("src").ifEmpty { element.attr("xlink:href") }.ifEmpty { element.attr("href") }
            .ifEmpty { element.attr("recindex") } // MOBI 6
        if (src.isEmpty()) return
        val image = resolveImage(src) ?: return
        if (image.bytes.size < MIN_IMAGE_BYTES || ImageTypes.extension(image.bytes) == null) return
        flushParagraph()
        blocks += ExtractedBlock.Image(image, element.attr("alt").takeIf { it.isNotBlank() })
    }

    private fun captionLastImage(caption: String) {
        val last = blocks.lastOrNull() as? ExtractedBlock.Image ?: return
        if (caption.isNotEmpty() && last.alt.isNullOrBlank()) blocks[blocks.lastIndex] = ExtractedBlock.Image(last.image, caption)
    }

    /** Bloque que es casi todo enlaces (tabla de contenidos, menú de navegación). */
    private fun isLinkList(element: Element): Boolean {
        val links = element.select("a[href]")
        if (links.size < MIN_LIST_LINKS) return false
        val total = element.text().length
        return total > 0 && links.sumOf { it.text().length } * 10 >= total * 8
    }

    private fun isCaption(element: Element): Boolean =
        element.normalName() == "figcaption" || element.classNames().any { it == "caption" || it == "figcaption" }

    /** Cabecera y licencia de Project Gutenberg, y marcadores de página del conversor de Gutenberg. */
    private fun isBoilerplate(element: Element): Boolean =
        element.id() in BOILERPLATE_IDS || element.classNames().any { it == "pg-boilerplate" || it == "x-ebookmaker-pageno" }

    private fun flushParagraph() {
        val text = clean(paragraph.toString())
        paragraph.clear()
        if (text.any { it.isLetterOrDigit() }) blocks += ExtractedBlock.Paragraph(text)
    }

    private fun closeChapter() {
        if (blocks.isNotEmpty()) chapters += ExtractedChapter(title, blocks)
        blocks = mutableListOf()
        title = null
    }

    private fun isHidden(element: Element): Boolean =
        element.hasAttr("hidden") ||
            element.attr("style").replace(" ", "").contains("display:none", ignoreCase = true) ||
            element.attr("epub:type").let { "footnote" in it || "endnote" in it || "pagebreak" in it } ||
            element.attr("role") == "doc-pagebreak"

    companion object {
        private const val MIN_IMAGE_BYTES = 2_048 // separadores y adornos
        private const val DROP_CAP_PUNCTUATION = "\"'“”‘’«»¡¿—-("

        private val HEADINGS = setOf("h1", "h2", "h3")
        private const val CAPTIONS = ".caption, figcaption"
        private const val MIN_LIST_LINKS = 3
        /** Contenedores de página: nunca se descartan enteros por tener enlaces. */
        private val CONTAINERS = setOf("body", "html", "main", "article", "section")
        private val BOILERPLATE_IDS = setOf("pg-header", "pg-footer", "pg-start-separator", "pg-end-separator")
        private val SKIPPED = setOf(
            "head", "script", "style", "noscript", "template", "nav", "sup", "math", "button", "form",
            "input", "select", "iframe", "audio", "video", "object",
        )
        private val BLOCKS = setOf(
            "p", "div", "section", "article", "main", "header", "footer", "aside", "blockquote", "pre",
            "li", "ul", "ol", "dl", "dt", "dd", "table", "tr", "td", "th", "figure", "figcaption",
            "h4", "h5", "h6", "hr", "address", "center", "body", "html", "svg", "caption",
        )

        private const val SOFT_HYPHEN = "­"
        private val WHITESPACE = Regex("\\s+")

        /** Colapsa espacios y quita guiones suaves, que parten palabras solo para el maquetado. */
        fun clean(text: String): String = text.replace(SOFT_HYPHEN, "").replace(WHITESPACE, " ").trim()
    }
}
