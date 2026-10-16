package com.linguareader.core.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Tiempo de una palabra de la frase (por posición en la frase) guardado junto al audio. */
@Serializable
data class SidecarEntry(
    val i: Int,
    val s: Long,
    val e: Long,
    val c: Float? = null,
    /** Hueco repartido matemáticamente entre vecinas (no medido por el modelo). */
    val est: Boolean = false,
)

/**
 * Alineación persistida de un segmento de audio. Vive en `<audio>.wav.align.json`, al lado del WAV de la
 * caché, y se borra con él. Solo es válida para el mismo audio ([wavBytes]) y el mismo alineador
 * ([signature]: modelo y revisión); si cambia cualquiera de los dos se recalcula.
 *
 * [ok] = false guarda un «no se pudo alinear de forma fiable» para no repetir el trabajo cada vez.
 */
@Serializable
data class SidecarData(
    val signature: String,
    val wavBytes: Long,
    val ok: Boolean,
    val entries: List<SidecarEntry> = emptyList(),
    val v: Int = 1,
)

object AlignmentSidecar {
    const val SUFFIX = ".align.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun fileFor(wav: File) = File(wav.path + SUFFIX)

    /** Alineación guardada para [wav] si existe y coincide con [signature] y con el audio actual; si no, null. */
    fun read(wav: File, signature: String): SidecarData? = runCatching {
        val f = fileFor(wav)
        if (!f.isFile) return null
        json.decodeFromString<SidecarData>(f.readText()).takeIf { it.v == 1 && it.signature == signature && it.wavBytes == wav.length() }
    }.getOrNull()

    /** Escritura atómica (`.part` → nombre final): una lectura nunca ve un fichero a medias. */
    fun write(wav: File, data: SidecarData) {
        val target = fileFor(wav)
        val tmp = File(target.path + ".${System.nanoTime()}.part")
        // Es solo una optimización: si la caché no se puede escribir, la alineación se recalcula la próxima vez.
        runCatching {
            tmp.writeText(json.encodeToString(SidecarData.serializer(), data))
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
        tmp.delete()
    }
}
