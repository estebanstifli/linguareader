package com.linguareader.engine.supertonic

import com.linguareader.core.platform.UnicodeForm
import com.linguareader.core.platform.charCountOf
import com.linguareader.core.platform.codePointAtIndex
import com.linguareader.core.platform.normalizeUnicode
import com.linguareader.core.platform.isLetterOrDigitCodePoint
import com.linguareader.core.platform.isMarkCodePoint
import com.linguareader.core.text.TokenSpan
import com.linguareader.core.text.Tokenizer
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

    data class Encoded(
        val ids: LongArray,
        val droppedChars: Int,
        /** One entry per model token, with original UTF-16 offsets (tags use -1). */
        val sourceStarts: IntArray = IntArray(0),
        val sourceEnds: IntArray = IntArray(0),
        val codePoints: IntArray = IntArray(0),
        val words: List<TokenSpan> = emptyList(),
        val timingSafe: Boolean = false,
    )

    private data class UnitSpan(val value: String, val start: Int, val end: Int)
    private data class Tracked(val units: List<UnitSpan>, val timingSafe: Boolean)

    fun encode(text: String, lang: String): Encoded {
        val tracked = tracked(text, lang)
        val ids = ArrayList<Long>(tracked.units.size)
        val starts = ArrayList<Int>(); val ends = ArrayList<Int>(); val codePoints = ArrayList<Int>()
        var dropped = 0
        var safe = tracked.timingSafe
        for (unit in tracked.units) {
            val cp = unit.value.codePointAtIndex(0)
            // La referencia Python trunca a uint16: fuera del BMP no hay entradas.
            val id = if (cp < indexer.size) indexer[cp] else -1L
            if (id >= 0) {
                ids += id; starts += unit.start; ends += unit.end; codePoints += cp
            } else {
                dropped++
                if (isLetterOrDigitCodePoint(cp) || isMarkCodePoint(cp)) safe = false
            }
        }
        return Encoded(ids.toLongArray(), dropped, starts.toIntArray(), ends.toIntArray(),
            codePoints.toIntArray(), Tokenizer.tokenize(text, lang), safe)
    }

    companion object {
        private val REPLACEMENTS = linkedMapOf(
            "–" to "-", "‑" to "-", "—" to "-", "_" to " ",
            "“" to "\"", "”" to "\"", "‘" to "'", "’" to "'",
            "´" to "'", "`" to "'", "[" to " ", "]" to " ", "|" to " ", "/" to " ", "#" to " ",
            "→" to " ", "←" to " ",
            // This punctuation is absent from the model's index. Keep its pause and source offset.
            "؛" to ";",
        )
        private val EXPRESSIONS = linkedMapOf("@" to " at ", "e.g.," to "for example, ", "i.e.," to "that is, ")
        private val SPECIAL = Regex("[♥☆♡©\\\\]")
        private val ENDS_WITH_PUNCT = Regex(".*[.!?;:,'\"“”‘’)\\]}…。」』】〉》›»]$")

        fun preprocess(input: String, lang: String): String = tracked(input, lang).units.joinToString("") { it.value }

        private fun tracked(input: String, lang: String): Tracked {
            require(lang in SupertonicModel.LANGUAGES || lang == "na") { "Idioma no soportado por Supertonic: $lang" }
            // NFKD can reorder marks across original codepoint boundaries. Stable queues preserve
            // provenance even for decomposed input; normalizing each character alone is insufficient.
            val provenance = HashMap<String, ArrayDeque<UnitSpan>>()
            var inputIndex = 0
            while (inputIndex < input.length) {
                val end = inputIndex + charCountOf(input.codePointAtIndex(inputIndex))
                val decomposed = normalizeUnicode(input.substring(inputIndex, end), UnicodeForm.NFKD)
                codePointStrings(decomposed).forEach { value ->
                    provenance.getOrPut(value) { ArrayDeque() }.addLast(UnitSpan(value, inputIndex, end))
                }
                inputIndex = end
            }
            var units = codePointStrings(normalizeUnicode(input, UnicodeForm.NFKD)).map { value ->
                requireNotNull(provenance[value]).removeFirst()
            }.filterNot { isEmoji(it.value.codePointAtIndex(0)) }
            var safe = true
            fun replace(pattern: Regex, replacement: String) {
                val text = units.joinToString("") { it.value }
                val matches = pattern.findAll(text).toList()
                if (matches.isEmpty()) return
                val positions = IntArray(units.size + 1)
                units.forEachIndexed { index, unit -> positions[index + 1] = positions[index] + unit.value.length }
                val out = ArrayList<UnitSpan>(); var cursor = 0
                for (match in matches) {
                    while (cursor < units.size && positions[cursor] < match.range.first) out += units[cursor++]
                    val start = cursor
                    while (cursor < units.size && positions[cursor] <= match.range.last) cursor++
                    val matched = units.subList(start, cursor)
                    val from = matched.minOf { it.start }; val to = matched.maxOf { it.end }
                    codePointStrings(replacement).forEach { out += UnitSpan(it, from, to) }
                }
                while (cursor < units.size) out += units[cursor++]
                units = out
            }
            REPLACEMENTS.forEach { (from, to) -> replace(Regex(Regex.escape(from)), to) }
            replace(SPECIAL, "")
            EXPRESSIONS.forEach { (from, to) ->
                if (units.joinToString("") { it.value }.contains(from)) safe = false
                replace(Regex(Regex.escape(from)), to)
            }
            listOf(",", ".", "!", "?", ";", ":", "'").forEach { replace(Regex(Regex.escape(" $it")), it) }
            for (quote in listOf("\"", "'")) {
                while (units.joinToString("") { it.value }.contains(quote + quote)) replace(Regex(Regex.escape(quote + quote)), quote)
            }
            replace(Regex("\\s+"), " ")
            units = units.dropWhile { it.value.isBlank() }.dropLastWhile { it.value.isBlank() }
            val text = units.joinToString("") { it.value }
            if (!ENDS_WITH_PUNCT.matches(text)) units = units + UnitSpan(".", -1, -1)
            fun tag(value: String) = codePointStrings(value).map { UnitSpan(it, -1, -1) }
            return Tracked(tag("<$lang>") + units + tag("</$lang>"), safe)
        }

        private fun codePointStrings(text: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                val start = i
                val cp = text.codePointAtIndex(i)
                i += charCountOf(cp)
                out += text.substring(start, i)
            }
            return out
        }

        private fun isEmoji(cp: Int): Boolean = cp in 0x1F600..0x1F64F || cp in 0x1F300..0x1F5FF || cp in 0x1F680..0x1F6FF ||
                    cp in 0x1F700..0x1F77F || cp in 0x1F780..0x1F7FF || cp in 0x1F800..0x1F8FF ||
                    cp in 0x1F900..0x1F9FF || cp in 0x1FA00..0x1FA6F || cp in 0x1FA70..0x1FAFF ||
                    cp in 0x2600..0x26FF || cp in 0x2700..0x27BF || cp in 0x1F1E6..0x1F1FF

        data class Chunk(val text: String, val start: Int, val end: Int)

        /** Trocea textos largos (> [maxLen]) por frases, comas y espacios. Las frases del lector suelen caber enteras. */
        fun chunk(text: String, maxLen: Int): List<String> = chunkWithOffsets(text, maxLen).map { it.text }

        /** Contiguous slices preserve exact requested UTF-16 offsets, including repeated whitespace. */
        fun chunkWithOffsets(text: String, maxLen: Int): List<Chunk> {
            require(maxLen >= 4)
            val out = mutableListOf<Chunk>(); var start = 0
            while (start < text.length) {
                while (start < text.length && text[start].isWhitespace()) start++
                if (start == text.length) break
                var end = minOf(text.length, start + maxLen)
                if (end < text.length) {
                    val sentence = (start + 1 until end).lastOrNull { text[it].isWhitespace() && text[it - 1] in ".!?;,。！？؛" }
                    val space = (start + 1 until end).lastOrNull { text[it].isWhitespace() }
                    end = sentence ?: space ?: end
                    if (end < text.length && text[end].isLowSurrogate()) end--
                    while (end > start && end < text.length && isMarkCodePoint(text.codePointAtIndex(end))) {
                        end--; if (end > start && text[end].isLowSurrogate()) end--
                    }
                    if (end <= start) throw IllegalArgumentException("Secuencia Unicode demasiado larga")
                }
                var trimmedEnd = end
                while (trimmedEnd > start && text[trimmedEnd - 1].isWhitespace()) trimmedEnd--
                if (trimmedEnd > start) out += Chunk(text.substring(start, trimmedEnd), start, trimmedEnd)
                start = end
            }
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
    data class Slice(val from: Int, val to: Int)

    fun trim(samples: FloatArray, sampleRate: Int, threshold: Float = 0.02f, marginMs: Int = 40, windowMs: Int = 10): FloatArray {
        val slice = bounds(samples, sampleRate, threshold, marginMs, windowMs)
        return if (slice.from == 0 && slice.to == samples.size) samples else samples.copyOfRange(slice.from, slice.to)
    }

    fun bounds(samples: FloatArray, sampleRate: Int, threshold: Float = 0.02f, marginMs: Int = 40, windowMs: Int = 10): Slice {
        if (samples.isEmpty()) return Slice(0, 0)
        val win = maxOf(1, sampleRate * windowMs / 1000)
        fun loud(start: Int): Boolean {
            val end = minOf(samples.size, start + win)
            for (i in start until end) if (kotlin.math.abs(samples[i]) > threshold) return true
            return false
        }
        var first = 0
        while (first < samples.size && !loud(first)) first += win
        if (first >= samples.size) return Slice(0, samples.size) // todo silencio: no tocar
        var last = ((samples.size - 1) / win) * win
        while (last > first && !loud(last)) last -= win
        val margin = sampleRate * marginMs / 1000
        val from = maxOf(0, first - margin)
        val to = minOf(samples.size, last + win + margin)
        return Slice(from, to)
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
