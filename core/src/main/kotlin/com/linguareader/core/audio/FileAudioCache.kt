package com.linguareader.core.audio

import java.io.File

/**
 * Caché de audio generado en disco. La clave la calcula [AudioCacheKey] (texto normalizado,
 * idioma, motor@versión de modelo, voz, velocidad y parámetros), de modo que cualquier cambio
 * produce otro fichero y nunca se reproduce audio desactualizado.
 *
 * - Escritura atómica: se escribe en `.part` y se renombra.
 * - LRU por fecha de último acceso; al superar [maxBytes] se borran los más antiguos.
 * - Hilo-seguro a nivel de proceso (métodos sincronizados); el contenido es inmutable por clave.
 */
class FileAudioCache(
    private val directory: File,
    private val maxBytes: Long,
    private val extension: String = "wav",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        directory.mkdirs()
        directory.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
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
    fun sizeBytes(): Long = entries().sumOf { it.length() }

    @Synchronized
    fun clear() {
        entries().forEach { it.delete() }
    }

    @Synchronized
    fun trim() {
        val files = entries().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun entries(): List<File> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".$extension") }?.toList().orEmpty()

    private companion object {
        val KEY_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
    }
}
