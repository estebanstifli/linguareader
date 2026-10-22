package com.linguareader.core.narration

import kotlin.math.roundToLong
/** Palabra con su tiempo absoluto dentro de la pista de narración. */
data class TimedWord(val text: String, val startMs: Long, val endMs: Long)

/**
 * Lector mínimo de subtítulos ASS/SSA con karaoke: `{\k}`, `{\kf}`, `{\K}` y `{\ko}` con la duración en centésimas.
 * Cada sílaba empieza donde acaba la anterior, a partir del inicio de su `Dialogue`; las sílabas sin espacio entre
 * ellas forman una sola palabra. Una línea sin etiquetas de karaoke reparte su duración entre sus palabras según
 * su número de letras (tiempo aproximado).
 */
object AssKaraoke {

    private val DEFAULT_FORMAT = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
    private val OVERRIDE = Regex("\\{([^}]*)\\}")
    private val KARAOKE = Regex("\\\\[kK][fFoO]?(\\d+)")

    fun parse(ass: String): List<TimedWord> {
        var format = DEFAULT_FORMAT
        var inEvents = false
        val out = mutableListOf<TimedWord>()
        for (raw in ass.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val fields = line.substringAfter(':').split(',', limit = format.size).map { it.trim() }
            if (fields.size < format.size) continue
            val start = parseTime(fields[format.indexOf("start")]) ?: continue
            val end = parseTime(fields[format.indexOf("end")]) ?: continue
            out += words(fields[format.indexOf("text")], start, end)
        }
        return out.sortedBy { it.startMs }
    }

    /** `H:MM:SS.cc` → ms. */
    fun parseTime(value: String): Long? {
        val parts = value.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (s * 1000).roundToLong()
    }

    private class Syllable(val centis: Int?, val text: StringBuilder = StringBuilder())

    private fun words(body: String, start: Long, end: Long): List<TimedWord> {
        val syllables = mutableListOf<Syllable>()
        var last = 0
        fun append(text: String) {
            if (text.isEmpty()) return
            if (syllables.isEmpty()) syllables += Syllable(null)
            syllables.last().text.append(text.replace("\\N", " ").replace("\\n", " ").replace("\\h", " "))
        }
        for (m in OVERRIDE.findAll(body)) {
            append(body.substring(last, m.range.first))
            KARAOKE.find(m.groupValues[1])?.let { syllables += Syllable(it.groupValues[1].toInt()) }
            last = m.range.last + 1
        }
        append(body.substring(last))
        if (syllables.none { it.centis != null }) return untimed(syllables.joinToString("") { it.text }, start, end)

        val out = mutableListOf<TimedWord>()
        val word = StringBuilder()
        var wordStart = 0L
        var wordEnd = 0L
        var t = start
        for (s in syllables) {
            val sStart = t
            val sEnd = t + (s.centis ?: 0) * 10L
            t = sEnd
            for (c in s.text) {
                if (c.isWhitespace()) {
                    if (word.isNotEmpty()) { out += TimedWord(word.toString(), wordStart, wordEnd); word.clear() }
                } else {
                    if (word.isEmpty()) wordStart = sStart
                    word.append(c)
                    wordEnd = sEnd
                }
            }
        }
        if (word.isNotEmpty()) out += TimedWord(word.toString(), wordStart, wordEnd)
        return out
    }

    private fun untimed(text: String, start: Long, end: Long): List<TimedWord> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letters = words.sumOf { it.length }.coerceAtLeast(1)
        var t = start.toDouble()
        val perLetter = (end - start).coerceAtLeast(0) / letters.toDouble()
        return words.map { w ->
            val s = t
            t += w.length * perLetter
            TimedWord(w, s.toLong(), t.toLong())
        }
    }
}
