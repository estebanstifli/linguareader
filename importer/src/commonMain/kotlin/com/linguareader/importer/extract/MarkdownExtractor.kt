package com.linguareader.importer.extract

import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedBlock
import com.linguareader.importer.ExtractedChapter
import com.linguareader.importer.ExtractedDocument

/** Markdown: `#`–`###` abren capítulo; se quitan marcas, enlaces, imágenes y bloques de código. */
class MarkdownExtractor : DocumentExtractor {
    override val id = "markdown"
    override val extensions = setOf("md", "markdown")

    override fun sniff(source: DocumentSource): Boolean = false

    override fun extract(source: DocumentSource): ExtractedDocument =
        parse(TextDecoding.decode(source.readBytes()), source.titleFromName)

    fun parse(raw: String, fallbackTitle: String?): ExtractedDocument {
        var lines = raw.replace("\r\n", "\n").split('\n')
        var frontTitle: String? = null
        var frontAuthor: String? = null
        var frontLanguage: String? = null
        if (lines.firstOrNull()?.trim() == "---") {
            val close = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (close >= 0) {
                lines.subList(1, close + 1).forEach { line ->
                    val key = line.substringBefore(':').trim().lowercase()
                    val value = line.substringAfter(':', "").trim().trim('"', '\'')
                    when (key) {
                        "title" -> frontTitle = value
                        "author" -> frontAuthor = value
                        "lang", "language" -> frontLanguage = value
                    }
                }
                lines = lines.drop(close + 2)
            }
        }

        val chapters = mutableListOf<ExtractedChapter>()
        var firstHeading: String? = null
        var title: String? = null
        var blocks = mutableListOf<ExtractedBlock>()
        val paragraph = StringBuilder()
        var inCode = false

        fun flush() {
            val text = inline(paragraph.toString())
            paragraph.clear()
            if (text.any { it.isLetterOrDigit() }) blocks += ExtractedBlock.Paragraph(text)
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
                flush()
                inCode = !inCode
                continue
            }
            if (inCode) continue
            val heading = HEADING.matchEntire(line.trim())
            when {
                heading != null && heading.groupValues[1].length <= 3 -> {
                    flush()
                    val text = inline(heading.groupValues[2])
                    if (firstHeading == null) firstHeading = text
                    if (blocks.isNotEmpty()) chapters += ExtractedChapter(title, blocks)
                    title = if (blocks.isEmpty() && title != null) "$title. $text" else text
                    blocks = mutableListOf()
                }
                line.isBlank() || RULE.matches(line.trim()) -> flush()
                LIST_ITEM.containsMatchIn(line) -> {
                    flush()
                    paragraph.append(line.replaceFirst(LIST_ITEM, ""))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim().removePrefix(">").trim())
                }
            }
        }
        flush()
        if (blocks.isNotEmpty()) chapters += ExtractedChapter(title, blocks)

        return ExtractedDocument(
            format = id,
            title = frontTitle ?: firstHeading ?: fallbackTitle,
            authors = listOfNotNull(frontAuthor),
            language = frontLanguage,
            chapters = chapters,
        )
    }

    private companion object {
        val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
        val RULE = Regex("^([-*_]\\s*){3,}$")
        val LIST_ITEM = Regex("^\\s*([-*+]|\\d+[.)])\\s+")
        // Corchetes escapados siempre: el regex ICU de Android es más estricto que el de la JVM.
        val IMAGE = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
        val LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
        val EMPHASIS = Regex("(\\*{1,3}|_{1,3}|~~|`)")
        val HTML_TAG = Regex("<[^>]+>")

        fun inline(text: String): String = text
            .replace(IMAGE, "")
            .replace(LINK, "$1")
            .replace(HTML_TAG, "")
            .replace(EMPHASIS, "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
