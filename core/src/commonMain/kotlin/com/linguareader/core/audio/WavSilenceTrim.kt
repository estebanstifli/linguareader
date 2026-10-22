package com.linguareader.core.audio

import com.linguareader.core.platform.asciiAt
import com.linguareader.core.platform.getIntLe
import com.linguareader.core.platform.getShortLe
import com.linguareader.core.platform.putIntLe
import com.linguareader.core.platform.putShortLe
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.math.abs

/**
 * Recorta en el propio fichero el silencio inicial y final de un WAV PCM de 16 bits (el que deja Android TTS en cada
 * frase), dejando un margen de [marginMs]. Las pausas entre frases las pone el reproductor de forma controlada, igual
 * que con Supertonic. Devuelve los ms quitados al principio (para desplazar las marcas de tiempo), 0 si no se tocó
 * y null si el formato no es PCM16 o no se pudo leer.
 */
object WavSilenceTrim {

    fun trim(
        file: Path,
        threshold: Int = 655,
        marginMs: Int = 40,
        windowMs: Int = 10,
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ): Long? = runCatching {
        val bytes = fileSystem.read(file) { readByteArray() }
        if (bytes.size < 44 || bytes.asciiAt(0, 4) != "RIFF" || bytes.asciiAt(8, 4) != "WAVE") return null
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = bytes.asciiAt(pos, 4)
            val size = bytes.getIntLe(pos + 4)
            if (id == "fmt ") {
                if (bytes.getShortLe(pos + 8).toInt() != 1) return null // solo PCM sin comprimir
                channels = bytes.getShortLe(pos + 10).toInt()
                rate = bytes.getIntLe(pos + 12)
                bits = bytes.getShortLe(pos + 22).toInt()
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
        fun loud(frame: Int) = (0 until channels).any { c -> abs(bytes.getShortLe(dataStart + frame * frameBytes + c * 2).toInt()) > threshold }
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
        val payload = (to - from) * frameBytes
        val out = ByteArray(44 + payload)
        "RIFF".encodeToByteArray().copyInto(out, 0)
        out.putIntLe(4, 36 + payload)
        "WAVE".encodeToByteArray().copyInto(out, 8)
        "fmt ".encodeToByteArray().copyInto(out, 12)
        out.putIntLe(16, 16)
        out.putShortLe(20, 1)
        out.putShortLe(22, channels)
        out.putIntLe(24, rate)
        out.putIntLe(28, rate * frameBytes)
        out.putShortLe(32, frameBytes)
        out.putShortLe(34, 16)
        "data".encodeToByteArray().copyInto(out, 36)
        out.putIntLe(40, payload)
        bytes.copyInto(out, 44, dataStart + from * frameBytes, dataStart + from * frameBytes + payload)
        val tmp = (file.toString() + ".trim").toPath()
        fileSystem.write(tmp) { write(out) }
        fileSystem.delete(file, mustExist = false)
        fileSystem.atomicMove(tmp, file)
        from * 1000L / rate
    }.getOrNull()
}
