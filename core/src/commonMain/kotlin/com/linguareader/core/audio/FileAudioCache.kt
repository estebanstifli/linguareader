package com.linguareader.core.audio

import com.linguareader.core.platform.epochMillis
import com.linguareader.core.platform.setFileLastModified
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.FileSystem
import okio.Path
import kotlin.random.Random

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
    private val directory: Path,
    /** Límite de tamaño en bytes; se consulta en cada recorte para poder cambiarlo en caliente. */
    private val maxBytesProvider: () -> Long,
    private val extension: String = "wav",
    private val clock: () -> Long = ::epochMillis,
    val fileSystem: FileSystem = FileSystem.SYSTEM,
    /** Marca la fecha de último acceso de una entrada (LRU). Inyectable para sistemas de ficheros falsos. */
    private val touch: (Path, Long) -> Boolean = ::setFileLastModified,
) {
    constructor(
        directory: Path,
        maxBytes: Long,
        extension: String = "wav",
        clock: () -> Long = ::epochMillis,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        touch: (Path, Long) -> Boolean = ::setFileLastModified,
    ) : this(directory, { maxBytes }, extension, clock, fileSystem, touch)

    private val lock = SynchronizedObject()

    init {
        fileSystem.createDirectories(directory)
        list().filter { it.name.endsWith(".part") }.forEach { fileSystem.delete(it, mustExist = false) }
        removeOrphanSidecars()
    }

    private fun fileFor(key: String): Path {
        require(key.matches(KEY_PATTERN)) { "Clave de caché inválida: $key" }
        return directory / "$key.$extension"
    }

    fun get(key: String): Path? = synchronized(lock) {
        val f = fileFor(key)
        if (sizeOrNull(f).let { it == null || it == 0L }) return null
        touch(f, clock())
        f
    }

    /** Fichero temporal donde escribir el audio de [key]; publicarlo con [commit] o borrarlo con [discard]. */
    fun tempFileFor(key: String): Path {
        fileFor(key) // valida la clave
        return directory / "$key.${uniqueSuffix()}.part"
    }

    /** Publica [tmp] como entrada de [key] de forma atómica y aplica el límite de tamaño. */
    fun commit(key: String, tmp: Path): Path = synchronized(lock) {
        val target = fileFor(key)
        fileSystem.delete(target, mustExist = false)
        sidecarsOf(target).forEach { fileSystem.delete(it, mustExist = false) } // audio nuevo: la alineación y las marcas anteriores ya no valen
        runCatching { fileSystem.atomicMove(tmp, target) }.getOrElse { error("No se pudo publicar $target: ${it.message}") }
        touch(target, clock())
        trim()
        target
    }

    fun discard(tmp: Path) {
        fileSystem.delete(tmp, mustExist = false)
    }

    /** Variante síncrona: escribe con [writer] y publica. */
    fun put(key: String, writer: (Path) -> Unit): Path {
        val tmp = tempFileFor(key)
        try {
            writer(tmp)
            return commit(key, tmp)
        } finally {
            discard(tmp)
        }
    }

    fun sizeBytes(): Long = synchronized(lock) { entries().sumOf { sizeOf(it) } }

    fun clear() = synchronized(lock) {
        entries().forEach { delete(it) }
        removeOrphanSidecars()
    }

    fun trim() = synchronized(lock) {
        val files = entries().sortedBy { fileSystem.metadataOrNull(it)?.lastModifiedAtMillis ?: 0L }
        var total = files.sumOf { sizeOf(it) }
        val max = maxBytesProvider()
        for (f in files) {
            if (total <= max) break
            total -= sizeOf(f)
            delete(f)
        }
    }

    /** Ficheros que acompañan a un audio: alineación ([AlignmentSidecar]) y marcas del motor ([SpokenMarksSidecar]). */
    private fun sidecarsOf(audio: Path) = listOf(AlignmentSidecar.fileFor(audio), SpokenMarksSidecar.fileFor(audio))

    private fun sizeOrNull(p: Path): Long? = fileSystem.metadataOrNull(p)?.takeIf { it.isRegularFile }?.size

    private fun sizeOf(audio: Path) = (sizeOrNull(audio) ?: 0L) + sidecarsOf(audio).sumOf { sizeOrNull(it) ?: 0L }

    private fun delete(audio: Path) {
        fileSystem.delete(audio, mustExist = false)
        sidecarsOf(audio).forEach { fileSystem.delete(it, mustExist = false) }
    }

    private fun removeOrphanSidecars() {
        val files = list()
        for (suffix in listOf(AlignmentSidecar.SUFFIX, SpokenMarksSidecar.SUFFIX)) {
            files.filter { it.name.endsWith(suffix) }
                .filter { !fileSystem.exists(directory / it.name.removeSuffix(suffix)) }
                .forEach { fileSystem.delete(it, mustExist = false) }
        }
    }

    private fun list(): List<Path> = fileSystem.listOrNull(directory).orEmpty()

    private fun entries(): List<Path> =
        list().filter { it.name.endsWith(".$extension") && fileSystem.metadataOrNull(it)?.isRegularFile == true }

    private companion object {
        val KEY_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
    }
}

/** Sufijo único para ficheros temporales (sustituye a `System.nanoTime()`). */
internal fun uniqueSuffix(): String = Random.nextLong().toULong().toString(16)
