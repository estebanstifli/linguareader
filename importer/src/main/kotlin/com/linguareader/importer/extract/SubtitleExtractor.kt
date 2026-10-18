package com.linguareader.importer.extract

import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedBlock
import com.linguareader.importer.ExtractedChapter
import com.linguareader.importer.ExtractedDocument

/**
 * Subtítulos SRT/WebVTT: útiles para estudiar el guion de una película o serie. Se descartan
 * números de cue, tiempos y etiquetas; los cues se agrupan en párrafos de pocas frases.
 */
class SubtitleExtractor : DocumentExtractor {
    override val id = "subtitles"
    override val extensions = setOf("srt", "vtt")

    override fun sniff(source: DocumentSource): Boolean {
        val head = TextDecoding.decode(source.head).trimStart('﻿', ' ', '\n', '\r')
        return head.startsWith("WEBVTT") || SRT_START.containsMatchIn(head.take(200))
    }

    override fun extract(source: DocumentSource): ExtractedDocument =
        parse(TextDecoding.decode(source.file.readBytes()), source.titleFromName)

    fun parse(raw: String, fallbackTitle: String?): ExtractedDocument {
        val cues = raw.replace("\r\n", "\n").split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val lines = block.lines().map(String::trim).filter { line ->
                line.isNotEmpty() && "-->" !in line && !line.all(Char::isDigit) &&
                    !line.startsWith("WEBVTT") && !line.startsWith("NOTE") && !line.startsWith("STYLE")
            }
            lines.joinToString(" ") { it.replace(TAGS, "").removePrefix("-").trim() }
                .replace(Regex("\\s+"), " ").trim()
                .takeIf { text -> text.any { it.isLetterOrDigit() } }
        }

        val paragraphs = mutableListOf<ExtractedBlock>()
        val current = mutableListOf<String>()
        for (cue in cues) {
            current += cue
            val endsSentence = cue.trimEnd('"', '»', '”', ')').lastOrNull()?.let { it in ".!?…" } == true
            if ((current.size >= CUES_PER_PARAGRAPH && endsSentence) || current.size >= MAX_CUES_PER_PARAGRAPH) {
                paragraphs += ExtractedBlock.Paragraph(current.joinToString(" "))
                current.clear()
            }
        }
        if (current.isNotEmpty()) paragraphs += ExtractedBlock.Paragraph(current.joinToString(" "))

        return ExtractedDocument(
            format = id,
            title = fallbackTitle,
            chapters = if (paragraphs.isEmpty()) emptyList() else listOf(ExtractedChapter(null, paragraphs)),
        )
    }

    private companion object {
        const val CUES_PER_PARAGRAPH = 5
        const val MAX_CUES_PER_PARAGRAPH = 12
        val SRT_START = Regex("^\\d+\\s*\\n\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->")
        // Llaves y corchetes escapados siempre: el regex ICU de Android es más estricto que el de la JVM.
        val TAGS = Regex("<[^>]+>|\\{\\\\[^\\}]*\\}")
    }
}
