package com.linguareader.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Recorta en el propio fichero el silencio inicial y final de un WAV PCM de 16 bits (el que deja Android TTS en cada
 * frase), dejando un margen de [marginMs]. Las pausas entre frases las pone el reproductor de forma controlada, igual
 * que con Supertonic. Devuelve los ms quitados al principio (para desplazar las marcas de tiempo), 0 si no se tocó
 * y null si el formato no es PCM16 o no se pudo leer.
 */
object WavSilenceTrim {

    fun trim(file: File, threshold: Int = 655, marginMs: Int = 40, windowMs: Int = 10): Long? = runCatching {
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            if (id == "fmt ") {
                if (b.getShort(pos + 8).toInt() != 1) return null // solo PCM sin comprimir
                channels = b.getShort(pos + 10).toInt()
                rate = b.getInt(pos + 12)
                bits = b.getShort(pos + 22).toInt()
            } else if (id == "data") {
                dataStart = pos + 8
                // Algunos motores dejan el tamaño a 0 o mayor que el fichero si escriben en flujo.
                dataSize = if (size <= 0 || dataStart + size > bytes.size) bytes.size - dataStart else size
                break
            }
            if (size < 0) return null
            pos += 8 + size + (size and 1)
        }
        if (dataStart < 0 || bits != 16 || channels <= 0 || rate <= 0) return null
        val frameBytes = 2 * channels
        val frames = dataSize / frameBytes
        if (frames == 0) return 0L
        fun loud(frame: Int) = (0 until channels).any { c -> abs(b.getShort(dataStart + frame * frameBytes + c * 2).toInt()) > threshold }
        val win = maxOf(1, rate * windowMs / 1000)
        fun loudWindow(start: Int) = (start until minOf(frames, start + win)).any(::loud)
        var first = 0
        while (first < frames && !loudWindow(first)) first += win
        if (first >= frames) return 0L // todo silencio: no tocar
        var last = ((frames - 1) / win) * win
        while (last > first && !loudWindow(last)) last -= win
        val margin = rate * marginMs / 1000
        val from = maxOf(0, first - margin)
        val to = minOf(frames, last + win + margin)
        if (from == 0 && to == frames) return 0L
        val out = ByteBuffer.allocate(44 + (to - from) * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val payload = (to - from) * frameBytes
        out.put("RIFF".toByteArray()).putInt(36 + payload).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(rate).putInt(rate * frameBytes).putShort(frameBytes.toShort()).putShort(16)
        out.put("data".toByteArray()).putInt(payload)
        out.put(bytes, dataStart + from * frameBytes, payload)
        val tmp = File(file.path + ".trim")
        tmp.writeBytes(out.array())
        if (!file.delete() || !tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        from * 1000L / rate
    }.getOrNull()
}
