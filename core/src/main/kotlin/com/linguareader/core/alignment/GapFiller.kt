package com.linguareader.core.alignment

import com.linguareader.core.engine.TimedToken
import com.linguareader.core.model.Token
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Completa los tiempos de las palabras que el alineador no marcó (números, símbolos, palabras que
 * el modelo no pudo situar) repartiendo matemáticamente el hueco que dejan las vecinas alineadas:
 *
 * - Hueco entre dos palabras alineadas: de **fin de la anterior** a **inicio de la siguiente**,
 *   repartido en proporción al número de letras de cada palabra sin alinear.
 * - Al principio de la frase: desde el inicio del audio hasta el inicio de la primera alineada.
 * - Al final: desde el fin de la última alineada hasta el final del audio.
 * - Sin ninguna palabra alineada: toda la frase, de principio a fin del audio, en proporción a las letras.
 *
 * Los tiempos añadidos van marcados con [TimedToken.estimated]: son una aproximación honesta, no
 * una medición. El resultado es monótono, contiguo en los huecos y cubre todas las palabras.
 */
object GapFiller {

    /** Margen de silencio que Supertonic deja recortado al inicio y final de cada clip (40 ms). */
    const val EDGE_MARGIN_MS = 40L

    /**
     * @param tokens palabras de la frase, en orden.
     * @param aligned tiempos reales del modelo (cualquier subconjunto de [tokens], ordenado por posición).
     * @param clipDurationMs duración del audio de la frase.
     */
    fun fill(tokens: List<Token>, aligned: List<TimedToken>, clipDurationMs: Long): List<TimedToken> {
        if (tokens.isEmpty()) return aligned
        val byId = aligned.associateBy { it.tokenId }
        val speechStart = minOf(EDGE_MARGIN_MS, clipDurationMs / 4)
        val speechEnd = (clipDurationMs - EDGE_MARGIN_MS).coerceAtLeast(speechStart)
        val out = ArrayList<TimedToken>(tokens.size)
        var cursor = speechStart // fin de la última palabra ya resuelta
        var i = 0
        while (i < tokens.size) {
            val known = byId[tokens[i].id]
            if (known != null) {
                out += known
                cursor = maxOf(cursor, known.endMs)
                i++
                continue
            }
            // Racha de palabras sin tiempo: [i, j)
            var j = i
            while (j < tokens.size && byId[tokens[j].id] == null) j++
            val gapStart = cursor
            val gapEnd = (if (j < tokens.size) byId.getValue(tokens[j].id).startMs else speechEnd).coerceAtLeast(gapStart)
            val weights = (i until j).map { tokens[it].text.count(Char::isLetterOrDigit).coerceAtLeast(1) }
            val total = weights.sum().toDouble()
            var acc = 0
            for (k in i until j) {
                val w = weights[k - i]
                val from = gapStart + ((gapEnd - gapStart) * acc / total).toLong()
                acc += w
                val to = gapStart + ((gapEnd - gapStart) * acc / total).toLong()
                out += TimedToken(tokens[k].id, from, to, confidence = null, estimated = true)
            }
            cursor = gapEnd
            i = j
        }
        return out
    }

    /** Duración en ms de un WAV PCM16 por su cabecera (frecuencia y tamaño del bloque `data`); null si no se puede leer. */
    fun wavDurationMs(file: File): Long? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val header = ByteArray(minOf(f.length(), 4096L).toInt()).also { f.readFully(it) }
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val sampleRate = b.getInt(24)
            val channels = b.getShort(22).toInt().coerceAtLeast(1)
            var pos = 12
            while (pos + 8 <= header.size) {
                val id = String(header, pos, 4)
                val size = b.getInt(pos + 4)
                if (id == "data") {
                    val bytes = minOf(size.toLong() and 0xFFFFFFFFL, f.length() - pos - 8)
                    return@use bytes * 1000 / (2L * channels * sampleRate)
                }
                pos += 8 + size
            }
            null
        }
    }.getOrNull()
}
