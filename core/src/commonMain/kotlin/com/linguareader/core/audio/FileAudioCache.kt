package com.linguareader.core.audio

import com.linguareader.core.content.Hashing
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
 * - **Fijados** ([pin]): claves que pertenecen a un grupo (el audio de un libro generado entero) no cuentan en el
 *   límite ni las expulsa el LRU; solo se borran con su grupo ([deleteGroup]). Los grupos se guardan en `pins/`.
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

    /**
     * Tamaño en disco según el último recuento, más lo publicado desde entonces. Solo sirve para decidir cuándo
     * recortar sin listar el directorio en cada clip: los sidecars que se escriben después de publicar no se
     * suman (son pequeños), así que el recorte real se hace con un recuento exacto en [trim].
     */
    private var estimatedBytes = 0L

    private val pinsDir = directory / "pins"

    /** Claves fijadas por grupo (en memoria; en disco, un fichero por grupo con una clave por línea). */
    private val pins = HashMap<String, MutableSet<String>>()

    init {
        fileSystem.createDirectories(directory)
        list().filter { it.name.endsWith(".part") }.forEach { fileSystem.delete(it, mustExist = false) }
        removeOrphanSidecars()
        loadPins()
        estimatedBytes = scan().filter { !isPinned(it.key) }.sumOf { it.bytes }
    }

    // ---------------------------------------------------------------------------------------------
    // Fijados por grupo
    // ---------------------------------------------------------------------------------------------

    private fun pinFile(group: String): Path = pinsDir / (Hashing.sha256Hex(group).take(32) + ".keys")

    private fun loadPins() {
        pins.clear()
        for (f in fileSystem.listOrNull(pinsDir).orEmpty()) {
            if (!f.name.endsWith(".keys")) continue
            val lines = runCatching { fileSystem.read(f) { readUtf8() } }.getOrNull()?.lines().orEmpty()
            val group = lines.firstOrNull()?.removePrefix(GROUP_HEADER) ?: continue
            pins[group] = lines.drop(1).filter { it.matches(KEY_PATTERN) }.toMutableSet()
        }
    }

    private fun savePins(group: String) {
        val keys = pins[group]
        val f = pinFile(group)
        if (keys.isNullOrEmpty()) {
            fileSystem.delete(f, mustExist = false)
            return
        }
        fileSystem.createDirectories(pinsDir)
        writeAtomically(fileSystem, f, (listOf(GROUP_HEADER + group) + keys.sorted()).joinToString("\n"))
    }

    private fun isPinned(key: String): Boolean = pins.values.any { key in it }

    /** Claves fijadas en [group] (vacío si no existe). */
    fun pinnedKeys(group: String): Set<String> = synchronized(lock) { pins[group]?.toSet().orEmpty() }

    /**
     * Fija [keys] en [group]: dejan de contar en el límite y el LRU no las expulsa. Las claves que aún no tienen
     * audio también se fijan (se protegerán en cuanto se publiquen).
     */
    fun pin(group: String, keys: Collection<String>) = synchronized(lock) {
        require(group.isNotBlank() && '\n' !in group) { "Grupo inválido" }
        val set = pins.getOrPut(group) { HashSet() }
        var freed = 0L
        for (k in keys) {
            require(k.matches(KEY_PATTERN)) { "Clave de caché inválida: $k" }
            if (set.add(k) && pins.values.none { it !== set && k in it }) freed += entryBytes(fileFor(k))
        }
        estimatedBytes = (estimatedBytes - freed).coerceAtLeast(0L)
        savePins(group)
    }

    /** Bytes en disco del audio (y sidecars) de las claves fijadas en [group]. */
    fun pinnedBytes(group: String): Long = synchronized(lock) { pins[group].orEmpty().sumOf { entryBytes(fileFor(it)) } }

    /** Bytes de todo lo fijado (cualquier grupo). */
    fun pinnedBytes(): Long = synchronized(lock) { pins.values.flatten().toSet().sumOf { entryBytes(fileFor(it)) } }

    /**
     * Quita el grupo y borra su audio, salvo lo que otro grupo también fije. Lo que no se borra vuelve a contar en
     * el límite como una entrada normal. Devuelve los bytes liberados.
     */
    fun deleteGroup(group: String): Long = synchronized(lock) {
        val keys = pins.remove(group) ?: return@synchronized 0L
        savePins(group)
        var freed = 0L
        for (k in keys) {
            if (isPinned(k)) continue
            val audio = fileFor(k)
            freed += entryBytes(audio)
            delete(audio)
        }
        freed
    }

    private fun entryBytes(audio: Path): Long = (sizeOrNull(audio) ?: 0L) + sidecarsOf(audio).sumOf { sizeOrNull(it) ?: 0L }

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

    /** true si hay audio para [key], sin marcarlo como usado (recuentos). */
    fun has(key: String): Boolean = synchronized(lock) { sizeOrNull(fileFor(key)).let { it != null && it > 0L } }

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
        if (!isPinned(key)) {
            estimatedBytes += sizeOrNull(target) ?: 0L
            if (estimatedBytes > maxBytesProvider()) trim()
        }
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

    /** Bytes de las entradas sujetas al límite (sin contar lo fijado; ver [pinnedBytes]). */
    fun sizeBytes(): Long = synchronized(lock) { scan().filter { !isPinned(it.key) }.sumOf { it.bytes }.also { estimatedBytes = it } }

    /** Vacía las entradas sujetas al límite; lo fijado se conserva (se borra por grupo con [deleteGroup]). */
    fun clear() = synchronized(lock) {
        scan().filter { !isPinned(it.key) }.forEach { delete(it.audio) }
        removeOrphanSidecars()
        estimatedBytes = 0L
    }

    /** Expulsa los menos usados hasta caber en el límite. Un recuento del directorio (una lectura de metadatos por fichero). */
    fun trim() = synchronized(lock) {
        val entries = scan().filter { !isPinned(it.key) }.sortedBy { it.lastModified }
        var total = entries.sumOf { it.bytes }
        val max = maxBytesProvider()
        for (e in entries) {
            if (total <= max) break
            total -= e.bytes
            delete(e.audio)
        }
        estimatedBytes = total
    }

    /** Ficheros que acompañan a un audio: alineación ([AlignmentSidecar]) y marcas del motor ([SpokenMarksSidecar]). */
    private fun sidecarsOf(audio: Path) = listOf(AlignmentSidecar.fileFor(audio), SpokenMarksSidecar.fileFor(audio))

    private fun sizeOrNull(p: Path): Long? = fileSystem.metadataOrNull(p)?.takeIf { it.isRegularFile }?.size

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

    /** Un audio con sus sidecars: bytes totales y fecha de último acceso del audio. */
    private class Entry(val audio: Path, val bytes: Long, val lastModified: Long) {
        val key: String get() = audio.name.substringBeforeLast('.')
    }

    /** Lee los metadatos de cada fichero del directorio una sola vez y agrupa cada audio con sus sidecars. */
    private fun scan(): List<Entry> {
        val sizes = HashMap<String, Long>()
        val modified = HashMap<String, Long>()
        for (p in list()) {
            val m = fileSystem.metadataOrNull(p) ?: continue
            if (!m.isRegularFile) continue
            sizes[p.name] = m.size ?: 0L
            modified[p.name] = m.lastModifiedAtMillis ?: 0L
        }
        return sizes.keys.filter { it.endsWith(".$extension") }.map { name ->
            val audio = directory / name
            Entry(audio, (sizes[name] ?: 0L) + sidecarsOf(audio).sumOf { sizes[it.name] ?: 0L }, modified[name] ?: 0L)
        }
    }

    private companion object {
        val KEY_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
        const val GROUP_HEADER = "group="
    }
}

/** Sufijo único para ficheros temporales (sustituye a `System.nanoTime()`). */
internal fun uniqueSuffix(): String = Random.nextLong().toULong().toString(16)
