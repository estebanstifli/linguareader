package com.linguareader.engine.supertonic

import com.linguareader.core.platform.UnicodeForm
import com.linguareader.core.platform.charCountOf
import com.linguareader.core.platform.codePointAtIndex
import com.linguareader.core.platform.normalizeUnicode
import com.linguareader.core.platform.putIntLe
import com.linguareader.core.platform.putShortLe
import okio.FileSystem
import okio.Path

/**
 * Port a Kotlin del preprocesado de texto de la implementación de referencia de Supertonic
 * (java/Helper.java, `UnicodeProcessor`). Diferencia deliberada: los caracteres que no están en
 * el índice (valor -1) se descartan en lugar de enviarse al modelo (en ONNX `Gather`, -1 leería
 * la última fila de la tabla de embeddings).
 */
class SupertonicTextProcessor(private val indexer: LongArray) {

    data class Encoded(val ids: LongArray, val droppedChars: Int)

    fun encode(text: String, lang: String): Encoded {
        val processed = preprocess(text, lang)
        val ids = ArrayList<Long>(processed.length)
        var dropped = 0
        var i = 0
        while (i < processed.length) {
            val cp = processed.codePointAtIndex(i)
            i += charCountOf(cp)
            // La referencia Python trunca a uint16: fuera del BMP no hay entradas.
            val id = if (cp < indexer.size) indexer[cp] else -1L
            if (id >= 0) ids += id else dropped++
        }
        return Encoded(ids.toLongArray(), dropped)
    }

    companion object {
        private val REPLACEMENTS = linkedMapOf(
            "–" to "-", "‑" to "-", "—" to "-", "_" to " ",
            "“" to "\"", "”" to "\"", "‘" to "'", "’" to "'",
            "´" to "'", "`" to "'", "[" to " ", "]" to " ", "|" to " ", "/" to " ", "#" to " ",
            "→" to " ", "←" to " ",
        )
        private val EXPRESSIONS = linkedMapOf("@" to " at ", "e.g.," to "for example, ", "i.e.," to "that is, ")
        private val SPECIAL = Regex("[♥☆♡©\\\\]")
        private val ENDS_WITH_PUNCT = Regex(".*[.!?;:,'\"“”‘’)\\]}…。」』】〉》›»]$")

        fun preprocess(input: String, lang: String): String {
            require(lang in SupertonicModel.LANGUAGES || lang == "na") { "Idioma no soportado por Supertonic: $lang" }
            var text = normalizeUnicode(input, UnicodeForm.NFKD)
            text = removeEmojis(text)
            REPLACEMENTS.forEach { (from, to) -> text = text.replace(from, to) }
            text = text.replace(SPECIAL, "")
            EXPRESSIONS.forEach { (from, to) -> text = text.replace(from, to) }
            text = text.replace(" ,", ",").replace(" .", ".").replace(" !", "!").replace(" ?", "?")
                .replace(" ;", ";").replace(" :", ":").replace(" '", "'")
            while ("\"\"" in text) text = text.replace("\"\"", "\"")
            while ("''" in text) text = text.replace("''", "'")
            text = text.replace(Regex("\\s+"), " ").trim()
            if (!ENDS_WITH_PUNCT.matches(text)) text += "."
            return "<$lang>$text</$lang>"
        }

        private fun removeEmojis(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val start = i
                val cp = text.codePointAtIndex(i)
                i += charCountOf(cp)
                val emoji = cp in 0x1F600..0x1F64F || cp in 0x1F300..0x1F5FF || cp in 0x1F680..0x1F6FF ||
                    cp in 0x1F700..0x1F77F || cp in 0x1F780..0x1F7FF || cp in 0x1F800..0x1F8FF ||
                    cp in 0x1F900..0x1F9FF || cp in 0x1FA00..0x1FA6F || cp in 0x1FA70..0x1FAFF ||
                    cp in 0x2600..0x26FF || cp in 0x2700..0x27BF || cp in 0x1F1E6..0x1F1FF
                if (!emoji) sb.append(text, start, i)
            }
            return sb.toString()
        }

        /** Trocea textos largos (> [maxLen]) por frases, comas y espacios. Las frases del lector suelen caber enteras. */
        fun chunk(text: String, maxLen: Int): List<String> {
            val t = text.trim()
            if (t.length <= maxLen) return listOf(t)
            val out = mutableListOf<String>()
            val current = StringBuilder()
            fun flush() { if (current.isNotBlank()) out += current.toString().trim(); current.setLength(0) }
            val pieces = t.split(Regex("(?<=[.!?;,])\\s+"))
            for (piece in pieces) {
                if (piece.length > maxLen) {
                    flush()
                    piece.split(Regex("\\s+")).forEach { w ->
                        if (current.length + w.length + 1 > maxLen) flush()
                        if (current.isNotEmpty()) current.append(' ')
                        current.append(w)
                    }
                    flush()
                    continue
                }
                if (current.length + piece.length + 1 > maxLen) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
            flush()
            return out
        }

        fun maxChunkLength(lang: String) = if (lang == "ko" || lang == "ja") 120 else 300
    }
}

/**
 * Recorta el silencio inicial y final que el modelo deja en cada enunciado (medido: 0,3–0,5 s al
 * principio y ~0,6 s al final). Las pausas entre frases las pone el reproductor de forma controlada.
 */
object AudioTrim {
    fun trim(samples: FloatArray, sampleRate: Int, threshold: Float = 0.02f, marginMs: Int = 40, windowMs: Int = 10): FloatArray {
        if (samples.isEmpty()) return samples
        val win = maxOf(1, sampleRate * windowMs / 1000)
        fun loud(start: Int): Boolean {
            val end = minOf(samples.size, start + win)
            for (i in start until end) if (kotlin.math.abs(samples[i]) > threshold) return true
            return false
        }
        var first = 0
        while (first < samples.size && !loud(first)) first += win
        if (first >= samples.size) return samples // todo silencio: no tocar
        var last = ((samples.size - 1) / win) * win
        while (last > first && !loud(last)) last -= win
        val margin = sampleRate * marginMs / 1000
        val from = maxOf(0, first - margin)
        val to = minOf(samples.size, last + win + margin)
        return samples.copyOfRange(from, to)
    }
}

/** Escribe PCM 16 bits mono en un WAV estándar (cabecera RIFF de 44 bytes). */
object WavWriter {
    fun write(file: Path, samples: FloatArray, sampleRate: Int, fileSystem: FileSystem = FileSystem.SYSTEM) {
        fileSystem.write(file) { write(encode(samples, sampleRate)) }
    }

    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArray(44 + dataBytes)
        "RIFF".encodeToByteArray().copyInto(out, 0)
        out.putIntLe(4, 36 + dataBytes)
        "WAVE".encodeToByteArray().copyInto(out, 8)
        "fmt ".encodeToByteArray().copyInto(out, 12)
        out.putIntLe(16, 16)
        out.putShortLe(20, 1)
        out.putShortLe(22, 1)
        out.putIntLe(24, sampleRate)
        out.putIntLe(28, sampleRate * 2)
        out.putShortLe(32, 2)
        out.putShortLe(34, 16)
        "data".encodeToByteArray().copyInto(out, 36)
        out.putIntLe(40, dataBytes)
        var pos = 44
        for (s in samples) {
            out.putShortLe(pos, (s * 32767f).coerceIn(-32768f, 32767f).toInt())
            pos += 2
        }
        return out
    }
}
