package com.linguareader.core.platform

import okio.Path
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

actual fun normalizeUnicode(text: String, form: UnicodeForm): String = Normalizer.normalize(
    text,
    when (form) {
        UnicodeForm.NFC -> Normalizer.Form.NFC
        UnicodeForm.NFD -> Normalizer.Form.NFD
        UnicodeForm.NFKC -> Normalizer.Form.NFKC
        UnicodeForm.NFKD -> Normalizer.Form.NFKD
    },
)

actual fun sentenceBreaks(text: String, languageTag: String): List<Int> {
    val iterator = BreakIterator.getSentenceInstance(Locale.forLanguageTag(languageTag))
    iterator.setText(text)
    val out = mutableListOf<Int>()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        out += end
        end = iterator.next()
    }
    if (out.lastOrNull() != text.length) out += text.length
    return out
}

actual fun isLetterOrDigitCodePoint(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint)

actual fun isMarkCodePoint(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun setFileLastModified(path: Path, epochMillis: Long): Boolean = path.toFile().setLastModified(epochMillis)

actual class Sha256 {
    private val digest = MessageDigest.getInstance("SHA-256")
    actual fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
    actual fun digest(): ByteArray = digest.digest()
}

actual fun decodeBytes(bytes: ByteArray, offset: Int, length: Int, charset: String): String? =
    runCatching { String(bytes, offset, length, Charset.forName(charset)) }.getOrNull()

actual fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()
