package com.linguareader.importer.extract

import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedBlock
import com.linguareader.importer.ExtractedChapter
import com.linguareader.importer.ExtractedDocument
import com.linguareader.core.platform.decodeLatin1

/**
 * Rich Text Format. Un intérprete mínimo de palabras de control basta para el texto: `\par`
 * separa párrafos, `\'xx` y `\uN` codifican caracteres, y los destinos que no son texto
 * (fuentes, colores, estilos, imágenes, campos, notas...) se saltan.
 */
class RtfExtractor : DocumentExtractor {
    override val id = "rtf"
    override val extensions = setOf("rtf")

    override fun sniff(source: DocumentSource): Boolean =
        source.head.decodeLatin1(0, minOf(5, source.head.size)) == "{\\rtf"

    override fun extract(source: DocumentSource): ExtractedDocument {
        val parsed = parse(source.readBytes().decodeLatin1())
        val chapters = mutableListOf<ExtractedChapter>()
        var title: String? = null
        var blocks = mutableListOf<ExtractedBlock>()
        parsed.paragraphs.forEach { paragraph ->
            if (TextExtractor.isChapterHeading(paragraph)) {
                if (blocks.isNotEmpty()) chapters += ExtractedChapter(title, blocks).also { blocks = mutableListOf() }
                title = paragraph
            } else if (paragraph.any { it.isLetterOrDigit() }) {
                blocks += ExtractedBlock.Paragraph(paragraph)
            }
        }
        if (blocks.isNotEmpty()) chapters += ExtractedChapter(title, blocks)
        return ExtractedDocument(
            format = id,
            title = parsed.title ?: source.titleFromName,
            authors = listOfNotNull(parsed.author),
            chapters = chapters,
        )
    }

    data class Parsed(val paragraphs: List<String>, val title: String?, val author: String?)

    private class Group(val skip: Boolean, val uc: Int, val capture: StringBuilder?)

    /** [raw] es el fichero leído como ISO-8859-1: un char por byte, como exige la sintaxis RTF. */
    fun parse(raw: String): Parsed {
        var charset = "windows-1252"
        val stack = ArrayDeque<Group>()
        var skip = false
        var uc = 1
        var capture: StringBuilder? = null
        var pendingSkip = 0 // caracteres de reserva tras \uN
        var newGroup = false
        val text = StringBuilder()
        val title = StringBuilder()
        val author = StringBuilder()

        fun emit(c: Char) {
            if (pendingSkip > 0) {
                pendingSkip--
                return
            }
            // \title y \author viven dentro de {\info}, que se salta: capturar tiene prioridad.
            capture?.let { it.append(c); return }
            if (!skip) text.append(c)
        }

        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when (c) {
                '{' -> {
                    stack.addLast(Group(skip, uc, capture))
                    newGroup = true
                    i++
                    continue
                }
                '}' -> {
                    stack.removeLastOrNull()?.let { skip = it.skip; uc = it.uc; capture = it.capture }
                    pendingSkip = 0
                }
                '\\' -> {
                    val next = raw.getOrNull(i + 1)
                    when {
                        next == null -> Unit
                        next == '\'' -> {
                            // Dos dígitos hex; si el fichero está mal formado no se consume más de lo válido.
                            var end = i + 2
                            while (end < minOf(i + 4, raw.length) && raw[end].isHexDigit()) end++
                            raw.substring(i + 2, end).toIntOrNull(16)?.let { TextDecoding.decodeAs(byteArrayOf(it.toByte()), 0, charset).firstOrNull()?.let(::emit) }
                            i = end
                            newGroup = false
                            continue
                        }
                        next == '*' -> if (newGroup) skip = true
                        next == '~' -> emit(' ')
                        next == '_' -> emit('-')
                        next == '-' -> Unit
                        next == '\n' || next == '\r' -> emit('\n')
                        !next.isLetter() -> emit(next)
                        else -> {
                            var j = i + 1
                            while (j < raw.length && raw[j].isLetter()) j++
                            val word = raw.substring(i + 1, j)
                            var k = j
                            if (k < raw.length && (raw[k] == '-' || raw[k].isDigit())) {
                                k++
                                while (k < raw.length && raw[k].isDigit()) k++
                            }
                            val param = raw.substring(j, k).toIntOrNull()
                            if (k < raw.length && raw[k] == ' ') k++
                            when {
                                word in DESTINATIONS_SKIPPED && newGroup -> skip = true
                                word == "title" && newGroup -> capture = title
                                word == "author" && newGroup -> capture = author
                                word == "par" || word == "line" || word == "sect" || word == "page" -> emit('\n')
                                word == "tab" || word == "cell" -> emit(' ')
                                word == "row" -> emit('\n')
                                word == "emdash" -> emit('—')
                                word == "endash" -> emit('–')
                                word == "lquote" -> emit('‘')
                                word == "rquote" -> emit('’')
                                word == "ldblquote" -> emit('“')
                                word == "rdblquote" -> emit('”')
                                word == "bullet" -> emit('•')
                                word == "uc" -> uc = param ?: 1
                                word == "ansicpg" && param != null ->
                                    charset = "windows-$param".takeIf(TextDecoding::supports) ?: charset
                                word == "u" && param != null -> {
                                    emit((if (param < 0) param + 65536 else param).toChar())
                                    pendingSkip = uc
                                }
                                word == "bin" && param != null -> k += param // datos binarios: se saltan
                            }
                            newGroup = false
                            i = k
                            continue
                        }
                    }
                    newGroup = false
                    i += 2
                    continue
                }
                '\r', '\n' -> Unit // los saltos reales del fichero no son significativos
                else -> emit(c)
            }
            newGroup = false
            i++
        }

        val paragraphs = text.split('\n').map { HtmlContentWalker.clean(it) }.filter { it.isNotEmpty() }
        return Parsed(
            paragraphs = paragraphs,
            title = HtmlContentWalker.clean(title.toString()).ifEmpty { null },
            author = HtmlContentWalker.clean(author.toString()).ifEmpty { null },
        )
    }

    private companion object {
        fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        val DESTINATIONS_SKIPPED = setOf(
            "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "headerl", "headerr", "headerf",
            "footer", "footerl", "footerr", "footerf", "footnote", "fldinst", "themedata", "colorschememapping",
            "datastore", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnsdecl", "latentstyles",
            "mmathPr", "pn", "pntext", "listtext", "filetbl", "revtbl", "annotation", "atnid", "atnauthor",
            "shp", "shpinst", "nonshppict", "bkmkstart", "bkmkend", "operator", "company", "doccomm", "keywords",
            "subject", "category", "comment", "hlinkbase", "xe", "tc", "txe", "background",
        )
    }
}
