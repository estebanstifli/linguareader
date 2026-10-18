package com.linguareader.importer.extract

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodifica texto plano sin charset declarado: BOM → UTF-8 estricto → windows-1252.
 * windows-1252 cubre el texto antiguo de Europa occidental (es/fr/de/it/pt); otros legados
 * (cp1251, Shift-JIS...) necesitarían un detector estadístico.
 */
object TextDecoding {

    fun decode(bytes: ByteArray): String {
        bom(bytes)?.let { (charset, skip) -> return String(bytes, skip, bytes.size - skip, charset) }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun bom(b: ByteArray): Pair<Charset, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
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
        bytes.size > 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        bytes.startsWith(0x42, 0x4D) -> "bmp"
        else -> null
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { this[it] == values[it].toByte() }
}
