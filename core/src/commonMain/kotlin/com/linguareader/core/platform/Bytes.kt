package com.linguareader.core.platform

/** Lectura/escritura little-endian sobre ByteArray (cabeceras WAV, ficheros binarios), sin `java.nio`. */
fun ByteArray.getShortLe(index: Int): Short =
    ((this[index].toInt() and 0xFF) or ((this[index + 1].toInt() and 0xFF) shl 8)).toShort()

fun ByteArray.getIntLe(index: Int): Int =
    (this[index].toInt() and 0xFF) or
        ((this[index + 1].toInt() and 0xFF) shl 8) or
        ((this[index + 2].toInt() and 0xFF) shl 16) or
        ((this[index + 3].toInt() and 0xFF) shl 24)

fun ByteArray.putShortLe(index: Int, value: Int) {
    this[index] = (value and 0xFF).toByte()
    this[index + 1] = ((value ushr 8) and 0xFF).toByte()
}

fun ByteArray.putIntLe(index: Int, value: Int) {
    this[index] = (value and 0xFF).toByte()
    this[index + 1] = ((value ushr 8) and 0xFF).toByte()
    this[index + 2] = ((value ushr 16) and 0xFF).toByte()
    this[index + 3] = ((value ushr 24) and 0xFF).toByte()
}

/** Texto ASCII de [length] bytes desde [index] (identificadores de bloque RIFF). */
fun ByteArray.asciiAt(index: Int, length: Int): String = decodeToString(index, index + length)

/** Hexadecimal en minúsculas (`%02x` por byte). */
fun ByteArray.toHexLower(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return out.toString()
}

/** Punto de código en [index] (combina pares subrogados), como `String.codePointAt`. */
fun String.codePointAtIndex(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
    }
    return high.code
}

/** Unidades UTF-16 que ocupa el punto de código, como `Character.charCount`. */
fun charCountOf(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

/** Lectura big-endian (cabeceras PDB/MOBI). */
fun ByteArray.getShortBe(index: Int): Short =
    (((this[index].toInt() and 0xFF) shl 8) or (this[index + 1].toInt() and 0xFF)).toShort()

fun ByteArray.getIntBe(index: Int): Int =
    ((this[index].toInt() and 0xFF) shl 24) or
        ((this[index + 1].toInt() and 0xFF) shl 16) or
        ((this[index + 2].toInt() and 0xFF) shl 8) or
        (this[index + 3].toInt() and 0xFF)

/** ISO-8859-1: un char por byte. */
fun ByteArray.decodeLatin1(offset: Int = 0, length: Int = size - offset): String {
    val chars = CharArray(length) { (this[offset + it].toInt() and 0xFF).toChar() }
    return chars.concatToString()
}

/** `URLDecoder.decode(s, "UTF-8")` sin `+` → espacio: solo escapes `%XX` (UTF-8). Devuelve [this] si está mal formado. */
fun String.percentDecode(): String {
    if (!contains('%')) return this
    val out = ByteArray(length)
    var n = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '%') {
            if (i + 2 >= length) return this
            val hi = this[i + 1].digitToIntOrNull(16) ?: return this
            val lo = this[i + 2].digitToIntOrNull(16) ?: return this
            out[n++] = ((hi shl 4) or lo).toByte()
            i += 3
        } else {
            val bytes = c.toString().encodeToByteArray()
            if (n + bytes.size > out.size) return this
            bytes.copyInto(out, n)
            n += bytes.size
            i++
        }
    }
    return runCatching { out.decodeToString(0, n, throwOnInvalidSequence = true) }.getOrDefault(this)
}
