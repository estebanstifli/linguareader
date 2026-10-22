package com.linguareader.importer.extract

import com.linguareader.core.platform.asciiAt
import com.linguareader.core.platform.decodeBytes
import com.linguareader.core.platform.decodeLatin1

/**
 * Decodifica texto plano sin charset declarado: BOM → UTF-8 estricto → windows-1252.
 * windows-1252 cubre el texto antiguo de Europa occidental (es/fr/de/it/pt); otros legados
 * (cp1251, Shift-JIS...) necesitarían un detector estadístico.
 */
object TextDecoding {

    fun decode(bytes: ByteArray): String {
        bom(bytes)?.let { (charset, skip) -> return decodeAs(bytes, skip, charset) }
        return strictUtf8(bytes, 0) ?: windows1252(bytes, 0, bytes.size)
    }

    /**
     * Documentos XML/HTML que declaran su codificación (`<?xml encoding="windows-1251"?>`, `<meta charset=…>`):
     * BOM → UTF-8 estricto → la declarada → windows-1252.
     */
    fun decodeMarkup(bytes: ByteArray): String {
        bom(bytes)?.let { (charset, skip) -> return decodeAs(bytes, skip, charset) }
        strictUtf8(bytes, 0)?.let { return it }
        declaredCharset(bytes)?.let { name -> decodeBytes(bytes, 0, bytes.size, name)?.let { return it } }
        return windows1252(bytes, 0, bytes.size)
    }

    /** Juego de caracteres declarado en la cabecera del documento (XML o HTML), si lo hay y no es UTF-8. */
    internal fun declaredCharset(bytes: ByteArray): String? {
        val head = bytes.decodeLatin1(0, minOf(bytes.size, 2048))
        val name = Regex("""(?:encoding|charset)\s*=\s*["']?\s*([A-Za-z0-9._:-]+)""", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1) ?: return null
        return name.takeUnless { it.equals("utf-8", ignoreCase = true) || it.equals("utf8", ignoreCase = true) }
    }

    /** Decodificación por nombre de charset; los que Kotlin común no sabe van a la plataforma, y si falla, Latin-1. */
    fun decodeAs(bytes: ByteArray, offset: Int, charset: String, length: Int = bytes.size - offset): String = when (charset.lowercase()) {
        "utf-8", "utf8" -> bytes.decodeToString(offset, offset + length)
        "utf-16le" -> utf16(bytes, offset, length, littleEndian = true)
        "utf-16be" -> utf16(bytes, offset, length, littleEndian = false)
        "iso-8859-1", "latin1", "us-ascii", "ascii" -> bytes.decodeLatin1(offset, length)
        "windows-1252", "cp1252" -> windows1252(bytes, offset, length)
        else -> decodeBytes(bytes, offset, length, charset) ?: bytes.decodeLatin1(offset, length)
    }

    /** true si [charset] se puede decodificar (en común o en la plataforma). */
    fun supports(charset: String): Boolean = when (charset.lowercase()) {
        "utf-8", "utf8", "utf-16le", "utf-16be", "iso-8859-1", "latin1", "us-ascii", "ascii", "windows-1252", "cp1252" -> true
        else -> decodeBytes(byteArrayOf(0x41), 0, 1, charset) != null
    }

    private fun strictUtf8(bytes: ByteArray, skip: Int): String? =
        try { bytes.decodeToString(skip, bytes.size, throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { null }

    private fun windows1252(bytes: ByteArray, offset: Int, length: Int): String =
        decodeBytes(bytes, offset, length, "windows-1252") ?: bytes.decodeLatin1(offset, length)

    private fun utf16(bytes: ByteArray, offset: Int, length: Int, littleEndian: Boolean): String {
        val chars = CharArray(length / 2) { i ->
            val a = bytes[offset + i * 2].toInt() and 0xFF
            val b = bytes[offset + i * 2 + 1].toInt() and 0xFF
            (if (littleEndian) (b shl 8) or a else (a shl 8) or b).toChar()
        }
        return chars.concatToString()
    }

    private fun bom(b: ByteArray): Pair<String, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> "utf-8" to 3
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> "utf-16le" to 2
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> "utf-16be" to 2
        else -> null
    }

    /** Heurística para "¿esto es texto?": sin NUL (salvo UTF-16 con BOM) y pocos caracteres de control. */
    fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        if (bom(head) != null) return true
        var control = 0
        for (byte in head) {
            val v = byte.toInt() and 0xFF
            if (v == 0) return false
            if (v < 0x09 || v in 0x0E..0x1F) control++
        }
        return control * 100 < head.size
    }
}

object ImageTypes {
    /** Extensión para una imagen decodificable por Android (BitmapFactory), o null (SVG y desconocidos). */
    fun extension(bytes: ByteArray): String? = when {
        bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
        bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"
        bytes.size > 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) && bytes.asciiAt(8, 4) == "WEBP" -> "webp"
        bytes.startsWith(0x42, 0x4D) -> "bmp"
        else -> null
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { this[it] == values[it].toByte() }
}
