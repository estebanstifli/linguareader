package com.linguareader.importer.extract

import com.fleeksoft.ksoup.Ksoup
import com.linguareader.core.platform.asciiAt
import com.linguareader.core.platform.getIntBe
import com.linguareader.core.platform.getShortBe
import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedDocument
import com.linguareader.importer.ExtractedImage
import com.linguareader.importer.ImportException
import okio.Buffer

/**
 * Kindle sin DRM: MOBI (.mobi/.prc) y KF8 (.azw3). Ambos son una base de datos Palm (PDB) cuyo
 * registro 0 describe el libro; el texto es HTML repartido en registros comprimidos con
 * PalmDOC (LZ77 sencillo). Basta con descomprimir, concatenar y pasar el HTML por el mismo
 * recorrido que EPUB. Las imágenes son registros referenciados por índice.
 *
 * No soportado: DRM (se rechaza con aviso), compresión HUFF/CDIC (rara, diccionarios Huffman)
 * y KFX, el formato moderno de Amazon, que no es PDB.
 */
class MobiExtractor : DocumentExtractor {
    override val id = "mobi"
    override val extensions = setOf("mobi", "prc", "azw", "azw3")

    override fun sniff(source: DocumentSource): Boolean =
        source.head.size >= 68 && source.head.asciiAt(60, 8).let { it == "BOOKMOBI" || it == "TEXtREAd" }

    override fun extract(source: DocumentSource): ExtractedDocument {
        if (source.sizeBytes > MAX_FILE_BYTES) corrupt("Fichero demasiado grande")
        val pdb = source.readBytes(MAX_FILE_BYTES)
        val book = parse(pdb)
        val html = Ksoup.parse(book.html, "")
        val walker = HtmlContentWalker(resolveImage = { src -> book.image(src) })
        return ExtractedDocument(
            format = id,
            title = book.title ?: source.titleFromName,
            authors = book.authors,
            language = book.language,
            chapters = walker.walk(html.body()),
            cover = book.cover,
        )
    }

    class MobiBook(
        val html: String,
        val title: String?,
        val authors: List<String>,
        val language: String?,
        val cover: ExtractedImage?,
        private val images: (Int) -> ExtractedImage?,
    ) {
        /** `recindex="00012"` (MOBI 6) o `kindle:embed:000C` (KF8, base 32) → imagen. */
        fun image(src: String): ExtractedImage? {
            val index = when {
                src.startsWith("kindle:embed:") -> src.removePrefix("kindle:embed:").substringBefore('?').toIntOrNull(32)
                else -> src.filter(Char::isDigit).toIntOrNull()
            } ?: return null
            return images(index)
        }
    }

    fun parse(pdb: ByteArray): MobiBook {
        if (pdb.size < 78) corrupt("Cabecera PDB incompleta")
        val type = pdb.asciiAt(60, 8)
        val recordCount = pdb.getShortBe(76).toInt() and 0xFFFF
        if (recordCount < 2) corrupt("El libro no tiene registros de texto")
        if (78 + recordCount * 8 > pdb.size) corrupt("Tabla de registros incompleta")
        val offsets = IntArray(recordCount) { pdb.getIntBe(78 + it * 8) }
        fun record(i: Int): ByteArray {
            val start = offsets[i]
            val end = if (i + 1 < recordCount) offsets[i + 1] else pdb.size
            if (start < 0 || end > pdb.size || start > end) corrupt("Registro $i fuera de rango")
            return pdb.copyOfRange(start, end)
        }

        val header = record(0)
        if (header.size < 16) corrupt("Registro 0 incompleto")
        val compression = header.getShortBe(0).toInt() and 0xFFFF
        val textRecords = header.getShortBe(8).toInt() and 0xFFFF
        val encryption = header.getShortBe(12).toInt() and 0xFFFF
        if (encryption != 0) {
            throw ImportException(ImportException.Reason.DRM_PROTECTED, "Este libro de Kindle tiene DRM. Solo se pueden importar libros sin DRM.")
        }
        if (compression == HUFF_CDIC) {
            throw ImportException(ImportException.Reason.UNSUPPORTED_FORMAT, "Compresión HUFF/CDIC no soportada: conviértelo a EPUB (p. ej. con Calibre).")
        }
        if (compression != NONE && compression != PALMDOC) corrupt("Compresión desconocida: $compression")

        val hasMobiHeader = type == "BOOKMOBI" && header.size >= 0x84 && header.asciiAt(16, 4) == "MOBI"
        var charset = "windows-1252"
        var extraFlags = 0
        var firstImage = -1
        var title: String? = null
        val authors = mutableListOf<String>()
        var language: String? = null
        var coverOffset = -1
        if (hasMobiHeader) {
            val headerLength = header.getIntBe(0x14)
            if (header.getIntBe(0x1C) == 65001) charset = "utf-8"
            firstImage = header.getIntBe(0x6C)
            if (headerLength >= 0xE4 && header.size >= 0xF4) extraFlags = header.getShortBe(0xF2).toInt() and 0xFFFF
            val nameOffset = header.getIntBe(0x54)
            val nameLength = header.getIntBe(0x58)
            if (nameOffset > 0 && nameLength > 0 && nameOffset + nameLength <= header.size) {
                title = TextDecoding.decodeAs(header, nameOffset, charset, nameLength).trim()
            }
            val exthFlags = header.getIntBe(0x80)
            val exthStart = 16 + headerLength
            if (exthFlags and 0x40 != 0 && exthStart + 12 <= header.size && header.asciiAt(exthStart, 4) == "EXTH") {
                val count = header.getIntBe(exthStart + 8)
                var pos = exthStart + 12
                repeat(count) {
                    if (pos + 8 > header.size) return@repeat
                    val recType = header.getIntBe(pos)
                    val len = header.getIntBe(pos + 4)
                    if (len < 8 || pos + len > header.size) return@repeat
                    val value = header.copyOfRange(pos + 8, pos + len)
                    when (recType) {
                        100 -> authors += TextDecoding.decodeAs(value, 0, charset).trim()
                        503 -> title = TextDecoding.decodeAs(value, 0, charset).trim()
                        524 -> language = TextDecoding.decodeAs(value, 0, charset).trim()
                        201 -> if (value.size >= 4) coverOffset = value.getIntBe(0)
                    }
                    pos += len
                }
            }
        }

        val text = Buffer()
        for (i in 1..minOf(textRecords, recordCount - 1)) {
            var bytes = record(i)
            bytes = bytes.copyOf(bytes.size - trailingSize(bytes, extraFlags))
            text.write(if (compression == PALMDOC) PalmDoc.decompress(bytes) else bytes)
        }

        fun image(index: Int): ExtractedImage? {
            if (firstImage <= 0 || index < 0) return null
            val record = firstImage + index - 1
            if (record !in 1 until recordCount) return null
            return record(record).takeIf { ImageTypes.extension(it) != null }?.let { ExtractedImage(it) }
        }

        return MobiBook(
            html = TextDecoding.decodeAs(text.readByteArray(), 0, charset),
            title = title?.takeIf { it.isNotBlank() },
            authors = authors.filter { it.isNotBlank() },
            language = language?.takeIf { it.isNotBlank() },
            cover = if (coverOffset >= 0) image(coverOffset + 1) else null,
            images = ::image,
        )
    }

    /** Bytes de relleno al final de cada registro de texto (índices de varios bytes y extras). */
    private fun trailingSize(record: ByteArray, flags: Int): Int {
        var size = 0
        for (bit in 1..15) {
            if (flags and (1 shl bit) == 0) continue
            // Entero de longitud variable leído hacia atrás: 7 bits por byte, el bit alto marca el inicio.
            var value = 0
            var shift = 0
            var pos = record.size - size
            while (pos > 0) {
                val b = record[--pos].toInt() and 0xFF
                value = value or ((b and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 != 0 || shift >= 28) break
            }
            size += value
        }
        if (flags and 1 != 0 && record.size - size - 1 >= 0) {
            size += (record[record.size - size - 1].toInt() and 0x3) + 1
        }
        return size.coerceIn(0, record.size)
    }

    private fun corrupt(message: String): Nothing = throw ImportException(ImportException.Reason.CORRUPT, message)

    private companion object {
        const val NONE = 1
        const val PALMDOC = 2
        const val HUFF_CDIC = 17480
        const val MAX_FILE_BYTES = 200L * 1024 * 1024
    }
}

/** Descompresión PalmDOC (variante de LZ77 con bytes de "espacio + carácter"). */
object PalmDoc {
    fun decompress(input: ByteArray): ByteArray {
        var buffer = ByteArray(input.size * 3 + 16)
        var length = 0
        fun put(b: Int) {
            if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[length++] = b.toByte()
        }
        var i = 0
        while (i < input.size) {
            val c = input[i++].toInt() and 0xFF
            when {
                c in 1..8 -> repeat(c) { if (i < input.size) put(input[i++].toInt() and 0xFF) } // literales
                c < 0x80 -> put(c) // 0x00 y 0x09–0x7F: el propio byte
                c >= 0xC0 -> { put(' '.code); put(c xor 0x80) } // espacio + carácter
                else -> { // 0x80–0xBF: referencia a lo ya escrito
                    if (i >= input.size) break
                    val pair = (c shl 8) or (input[i++].toInt() and 0xFF)
                    val distance = (pair shr 3) and 0x7FF
                    val count = (pair and 0x7) + 3
                    if (distance == 0 || distance > length) continue
                    repeat(count) { put(buffer[length - distance].toInt() and 0xFF) }
                }
            }
        }
        return buffer.copyOf(length)
    }
}
