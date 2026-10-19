package com.linguareader.core.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Marca que da el propio motor al generar el audio: el trozo [start, end) del texto hablado empieza a sonar a los
 * [ms] milisegundos del clip (Android TTS: `onRangeStart` con la posición en frames del audio).
 */
@Serializable
data class SpokenMark(val start: Int, val end: Int, val ms: Long)

@Serializable
private data class SpokenMarksData(val marks: List<SpokenMark>, val v: Int = 1)

/**
 * Marcas del motor guardadas junto al WAV de la caché (`<audio>.wav.marks.json`), igual que [AlignmentSidecar]:
 * se publican con el audio y se borran con él. Son del audio exacto que se generó, así que no caducan.
 */
object SpokenMarksSidecar {
    const val SUFFIX = ".marks.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun fileFor(wav: File) = File(wav.path + SUFFIX)

    fun read(wav: File): List<SpokenMark>? = runCatching {
        val f = fileFor(wav)
        if (!f.isFile) return null
        json.decodeFromString(SpokenMarksData.serializer(), f.readText()).takeIf { it.v == 1 }?.marks
    }.getOrNull()

    /** Escritura atómica; si falla, el clip simplemente se resaltará por frase (o con el alineador). */
    fun write(wav: File, marks: List<SpokenMark>) {
        val target = fileFor(wav)
        val tmp = File(target.path + ".${System.nanoTime()}.part")
        runCatching {
            tmp.writeText(json.encodeToString(SpokenMarksData.serializer(), SpokenMarksData(marks)))
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
        tmp.delete()
    }
}
