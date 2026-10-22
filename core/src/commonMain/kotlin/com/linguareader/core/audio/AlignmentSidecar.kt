package com.linguareader.core.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

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

    fun fileFor(wav: Path): Path = (wav.toString() + SUFFIX).toPath()

    /** Alineación guardada para [wav] si existe y coincide con [signature] y con el audio actual; si no, null. */
    fun read(fileSystem: FileSystem, wav: Path, signature: String): SidecarData? = runCatching {
        val f = fileFor(wav)
        if (fileSystem.metadataOrNull(f)?.isRegularFile != true) return null
        val wavBytes = fileSystem.metadataOrNull(wav)?.size ?: return null
        json.decodeFromString<SidecarData>(fileSystem.read(f) { readUtf8() })
            .takeIf { it.v == 1 && it.signature == signature && it.wavBytes == wavBytes }
    }.getOrNull()

    /** Escritura atómica (`.part` → nombre final): una lectura nunca ve un fichero a medias. */
    fun write(fileSystem: FileSystem, wav: Path, data: SidecarData) {
        writeAtomically(fileSystem, fileFor(wav), json.encodeToString(SidecarData.serializer(), data))
    }
}

/**
 * Escribe [text] en [target] pasando por un `.part` y renombrando. Es solo una optimización (sidecars de la caché):
 * si falla, no se propaga; lo que se guardaba se recalcula la próxima vez.
 */
internal fun writeAtomically(fileSystem: FileSystem, target: Path, text: String) {
    val tmp = (target.toString() + "." + uniqueSuffix() + ".part").toPath()
    runCatching {
        fileSystem.write(tmp) { writeUtf8(text) }
        fileSystem.delete(target, mustExist = false)
        fileSystem.atomicMove(tmp, target)
    }
    fileSystem.delete(tmp, mustExist = false)
}
