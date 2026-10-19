package com.linguareader.core.audio

import java.io.File

/**
 * Caché de audio generado en disco. La clave la calcula [AudioCacheKey] (texto normalizado,
 * idioma, motor@versión de modelo, voz, velocidad y parámetros), de modo que cualquier cambio
 * produce otro fichero y nunca se reproduce audio desactualizado.
 *
 * - Escritura atómica: se escribe en `.part` y se renombra.
 * - LRU por fecha de último acceso; al superar el límite se borran los más antiguos. Cada entrada
 *   incluye su alineación ([AlignmentSidecar]) y las marcas del motor si las hay ([SpokenMarksSidecar]): cuenta en el tamaño y se borra junto al audio, de modo
 *   que un segmento ya generado y alineado no se rehace hasta que el LRU lo expulse.
 * - Hilo-seguro a nivel de proceso (métodos sincronizados); el contenido es inmutable por clave.
 */
class FileAudioCache(
    private val directory: File,
    /** Límite de tamaño en bytes; se consulta en cada recorte para poder cambiarlo en caliente. */
    private val maxBytesProvider: () -> Long,
    private val extension: String = "wav",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(directory: File, maxBytes: Long, extension: String = "wav", clock: () -> Long = System::currentTimeMillis) :
        this(directory, { maxBytes }, extension, clock)

    init {
        directory.mkdirs()
        directory.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        removeOrphanSidecars()
    }

    private fun fileFor(key: String): File {
        require(key.matches(KEY_PATTERN)) { "Clave de caché inválida: $key" }
        return File(directory, "$key.$extension")
    }

    @Synchronized
    fun get(key: String): File? {
        val f = fileFor(key)
        if (!f.isFile || f.length() == 0L) return null
        f.setLastModified(clock())
        return f
    }

    /** Fichero temporal donde escribir el audio de [key]; publicarlo con [commit] o borrarlo con [discard]. */
    fun tempFileFor(key: String): File {
        fileFor(key) // valida la clave
        return File(directory, "$key.${System.nanoTime()}.part")
    }

    /** Publica [tmp] como entrada de [key] de forma atómica y aplica el límite de tamaño. */
    @Synchronized
    fun commit(key: String, tmp: File): File {
        val target = fileFor(key)
        if (target.exists()) target.delete()
        sidecarsOf(target).forEach { it.delete() } // audio nuevo: la alineación y las marcas anteriores ya no valen
        check(tmp.renameTo(target)) { "No se pudo publicar $target" }
        target.setLastModified(clock())
        trim()
        return target
    }

    fun discard(tmp: File) {
        tmp.delete()
    }

    /** Variante síncrona: escribe con [writer] y publica. */
    fun put(key: String, writer: (File) -> Unit): File {
        val tmp = tempFileFor(key)
        try {
            writer(tmp)
            return commit(key, tmp)
        } finally {
            discard(tmp)
        }
    }

    @Synchronized
    fun sizeBytes(): Long = entries().sumOf { sizeOf(it) }

    @Synchronized
    fun clear() {
        entries().forEach { delete(it) }
        removeOrphanSidecars()
    }

    @Synchronized
    fun trim() {
        val files = entries().sortedBy { it.lastModified() }
        var total = files.sumOf { sizeOf(it) }
        val max = maxBytesProvider()
        for (f in files) {
            if (total <= max) break
            total -= sizeOf(f)
            delete(f)
        }
    }

    /** Ficheros que acompañan a un audio: alineación ([AlignmentSidecar]) y marcas del motor ([SpokenMarksSidecar]). */
    private fun sidecarsOf(audio: File) = listOf(AlignmentSidecar.fileFor(audio), SpokenMarksSidecar.fileFor(audio))

    private fun sizeOf(audio: File) = audio.length() + sidecarsOf(audio).sumOf { it.length() }

    private fun delete(audio: File) {
        audio.delete()
        sidecarsOf(audio).forEach { it.delete() }
    }

    private fun removeOrphanSidecars() {
        for (suffix in listOf(AlignmentSidecar.SUFFIX, SpokenMarksSidecar.SUFFIX)) {
            directory.listFiles { f -> f.name.endsWith(suffix) }
                ?.filter { !File(it.path.removeSuffix(suffix)).exists() }
                ?.forEach { it.delete() }
        }
    }

    private fun entries(): List<File> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".$extension") }?.toList().orEmpty()

    private companion object {
        val KEY_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
    }
}
