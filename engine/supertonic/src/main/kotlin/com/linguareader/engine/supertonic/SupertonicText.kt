package com.linguareader.engine.supertonic

import java.text.Normalizer

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
            val cp = processed.codePointAt(i)
            i += Character.charCount(cp)
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
            var text = Normalizer.normalize(input, Normalizer.Form.NFKD)
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
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                val emoji = cp in 0x1F600..0x1F64F || cp in 0x1F300..0x1F5FF || cp in 0x1F680..0x1F6FF ||
                    cp in 0x1F700..0x1F77F || cp in 0x1F780..0x1F7FF || cp in 0x1F800..0x1F8FF ||
                    cp in 0x1F900..0x1F9FF || cp in 0x1FA00..0x1FA6F || cp in 0x1FA70..0x1FAFF ||
                    cp in 0x2600..0x26FF || cp in 0x2700..0x27BF || cp in 0x1F1E6..0x1F1FF
                if (!emoji) sb.appendCodePoint(cp)
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
    fun write(file: java.io.File, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val buf = java.nio.ByteBuffer.allocate(44 + dataBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataBytes)
        for (s in samples) buf.putShort((s * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        file.writeBytes(buf.array())
    }
}
