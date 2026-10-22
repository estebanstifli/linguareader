package com.linguareader.core.platform

import okio.Path

/*
 * Puerto de plataforma de `core`: lo único que el código compartido no puede hacer en Kotlin común.
 * JVM/Android usa `java.*` (idéntico al comportamiento anterior); iOS usa Foundation y CommonCrypto.
 */

enum class UnicodeForm { NFC, NFD, NFKC, NFKD }

/** Normalización Unicode (`java.text.Normalizer` / `NSString`). */
expect fun normalizeUnicode(text: String, form: UnicodeForm): String

/**
 * Finales de frase del texto según el locale (`BreakIterator` de frases en JVM/ICU; enumeración por frases de
 * Foundation en iOS), como offsets UTF-16 crecientes. El último es `text.length`.
 */
expect fun sentenceBreaks(text: String, languageTag: String): List<Int>

/** `Character.isLetterOrDigit(codePoint)`: también para puntos de código fuera del BMP. */
expect fun isLetterOrDigitCodePoint(codePoint: Int): Boolean

/** Marcas combinantes (Mn, Mc, Me): forman parte de la palabra a la que acompañan. */
expect fun isMarkCodePoint(codePoint: Int): Boolean

/** Milisegundos desde la época Unix (reloj de pared; para el LRU de la caché). */
expect fun epochMillis(): Long

/** Cambia la fecha de modificación de un fichero (LRU de la caché de audio). false si no se pudo. */
expect fun setFileLastModified(path: Path, epochMillis: Long): Boolean

/** SHA-256 incremental (`MessageDigest` / CommonCrypto). */
expect class Sha256() {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
    fun digest(): ByteArray
}

/**
 * Decodifica [length] bytes desde [offset] con un juego de caracteres por nombre IANA ("windows-1252",
 * "windows-1251", "ISO-8859-15"...). null si la plataforma no lo conoce. UTF-8/UTF-16/Latin-1 se resuelven en
 * Kotlin común ([com.linguareader.core.platform.decodeLatin1]); esto es para los legados de Windows y similares.
 */
expect fun decodeBytes(bytes: ByteArray, offset: Int, length: Int, charset: String): String?

/** Núcleos disponibles (hilos de inferencia por defecto). */
expect fun availableProcessors(): Int
