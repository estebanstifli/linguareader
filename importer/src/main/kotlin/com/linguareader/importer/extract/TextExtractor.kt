package com.linguareader.importer.extract

import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedBlock
import com.linguareader.importer.ExtractedChapter
import com.linguareader.importer.ExtractedDocument

/**
 * Texto plano. Admite los dos estilos habituales: párrafos separados por línea en blanco con
 * líneas cortadas a mano (Project Gutenberg) y un párrafo por línea. Detecta títulos de capítulo
 * ("Chapter IV", "Capítulo 3", "CHAPITRE PREMIER"...) y los metadatos de Gutenberg.
 */
class TextExtractor : DocumentExtractor {
    override val id = "txt"
    override val extensions = setOf("txt", "text")

    /** Solo por extensión: aceptar "cualquier cosa que parezca texto" taparía formatos no soportados. */
    override fun sniff(source: DocumentSource): Boolean = false

    override fun extract(source: DocumentSource): ExtractedDocument =
        parse(TextDecoding.decode(source.file.readBytes()), source.titleFromName)

    /**
     * [blankLineParagraphs]: null = detectarlo; true = los párrafos van separados por línea en
     * blanco y las líneas internas están cortadas (PDF), aunque haya pocas líneas en blanco.
     */
    fun parse(raw: String, fallbackTitle: String?, blankLineParagraphs: Boolean? = null): ExtractedDocument {
        val gutenberg = GutenbergText.parse(raw)
        val lines = gutenberg.body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blankSeparated = blankLineParagraphs ?: (lines.count { it.isBlank() } * 20 > lines.size)

        val paragraphs = mutableListOf<String>()
        if (blankSeparated) {
            val current = StringBuilder()
            for (line in lines) {
                if (line.isBlank()) {
                    if (current.isNotEmpty()) paragraphs += current.toString().also { current.clear() }
                } else {
                    joinWrapped(current, line.trim())
                }
            }
            if (current.isNotEmpty()) paragraphs += current.toString()
        } else {
            lines.mapNotNullTo(paragraphs) { it.trim().takeIf(String::isNotEmpty) }
        }

        val chapters = mutableListOf<ExtractedChapter>()
        var title: String? = null
        var blocks = mutableListOf<ExtractedBlock>()
        for (paragraph in paragraphs) {
            if (isChapterHeading(paragraph)) {
                if (blocks.isNotEmpty()) chapters += ExtractedChapter(title, blocks)
                title = if (blocks.isEmpty() && title != null) "$title. $paragraph" else paragraph
                blocks = mutableListOf()
            } else if (paragraph.any { it.isLetterOrDigit() }) {
                blocks += ExtractedBlock.Paragraph(paragraph)
            }
        }
        if (blocks.isNotEmpty()) chapters += ExtractedChapter(title, blocks)

        return ExtractedDocument(
            format = id,
            title = gutenberg.title ?: fallbackTitle,
            authors = listOfNotNull(gutenberg.author),
            language = gutenberg.language,
            chapters = chapters,
        )
    }

    companion object {
        private val CHAPTER = Regex(
            "^(chapter|chap\\.|capítulo|capitulo|cap\\.|chapitre|kapitel|capitolo|part|parte|partie|teil|book|libro|livre|prologue|prólogo|prologo|epilogue|epílogo|épilogue|epilogo)\\b.{0,60}$",
            RegexOption.IGNORE_CASE,
        )
        private val ROMAN_OR_NUMBER = Regex("^([IVXLCDM]+|\\d{1,3})\\.?$")

        fun isChapterHeading(paragraph: String): Boolean =
            paragraph.length <= 70 && (CHAPTER.matches(paragraph) || ROMAN_OR_NUMBER.matches(paragraph))

        /** Une una línea cortada a mano, recomponiendo palabras partidas con guion ("exam-" + "ple"). */
        fun joinWrapped(target: StringBuilder, line: String) {
            if (target.isEmpty()) {
                target.append(line)
                return
            }
            val last = target.length - 1
            val hyphenated = target[last] == '-' && last > 0 && target[last - 1].isLetter() &&
                line.firstOrNull()?.isLowerCase() == true
            if (hyphenated) target.setLength(last) else target.append(' ')
            target.append(line)
        }
    }
}

/** Cabecera y licencia de Project Gutenberg: se extraen los metadatos y se recorta el cuerpo. */
internal object GutenbergText {

    data class Parsed(val body: String, val title: String?, val author: String?, val language: String?)

    private val START = Regex("\\*\\*\\*\\s*START OF (THE|THIS) PROJECT GUTENBERG[^\\n]*", RegexOption.IGNORE_CASE)
    private val END = Regex("\\*\\*\\*\\s*END OF (THE|THIS) PROJECT GUTENBERG", RegexOption.IGNORE_CASE)

    private val LANGUAGES = mapOf(
        "english" to "en", "french" to "fr", "spanish" to "es", "german" to "de", "italian" to "it",
        "portuguese" to "pt", "dutch" to "nl", "catalan" to "ca",
    )

    fun parse(text: String): Parsed {
        val start = START.find(text) ?: return Parsed(text, null, null, null)
        val header = text.substring(0, start.range.first)
        val end = END.find(text, start.range.last)?.range?.first ?: text.length
        fun field(name: String) = Regex("^$name:\\s*(.+)$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .find(header)?.groupValues?.get(1)?.trim()
        return Parsed(
            body = text.substring(start.range.last + 1, end),
            title = field("Title"),
            author = field("Author"),
            language = field("Language")?.lowercase()?.let(LANGUAGES::get),
        )
    }
}
