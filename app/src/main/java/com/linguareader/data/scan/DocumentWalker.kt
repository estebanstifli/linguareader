package com.linguareader.data.scan

import java.io.File

/**
 * Recorre carpetas buscando documentos que la app sabe abrir. Sin dependencias de Android para probarlo en la JVM.
 *
 * - [extensions]: extensiones aceptadas, en minúscula y sin punto ("epub", "pdf"...).
 * - Carpetas ocultas (nombre que empieza por punto) solo si [includeHidden]; también ficheros ocultos.
 * - [exclusions]: rutas absolutas que no se recorren, ni sus subcarpetas.
 * - Se saltan `Android/data` y `Android/obb` (datos privados de otras apps) y los enlaces que forman ciclos.
 * - Un .txt de menos de [MIN_TXT_BYTES] no se considera documento (notas, registros y ficheros de control).
 */
class DocumentWalker(
    private val extensions: Set<String>,
    private val includeHidden: Boolean,
    exclusions: Collection<String>,
) {
    private val excluded = exclusions.map { it.trimEnd('/') }.filter { it.isNotEmpty() }

    /** Documento encontrado: el fichero y su formato. */
    data class Found(val file: File, val format: String)

    /**
     * Recorre [root]. [onFolder] se llama al entrar en cada carpeta (para mostrar progreso); [cancelled] se consulta a
     * menudo para poder parar a medias.
     */
    fun walk(root: File, cancelled: () -> Boolean = { false }, onFolder: (File) -> Unit = {}, onFound: (Found) -> Unit) {
        val visited = HashSet<String>()
        val pending = ArrayDeque<File>().apply { add(root) }
        while (pending.isNotEmpty() && !cancelled()) {
            val dir = pending.removeLast()
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: continue
            if (!visited.add(canonical) || isExcluded(dir.absolutePath) || isExcluded(canonical)) continue
            onFolder(dir)
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (!includeHidden && child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (!isPrivateAppData(child)) pending.add(child)
                } else {
                    formatOf(child.name)?.takeIf { it in extensions && isWorthIt(child, it) }?.let { onFound(Found(child, it)) }
                }
            }
        }
    }

    private fun isExcluded(path: String) = excluded.any { path == it || path.startsWith("$it/") }

    private fun isPrivateAppData(dir: File) =
        (dir.name == "data" || dir.name == "obb") && dir.parentFile?.name == "Android"

    private fun isWorthIt(file: File, format: String) = format != "txt" || file.length() >= MIN_TXT_BYTES

    companion object {
        const val MIN_TXT_BYTES = 1024L

        /** Formato por la extensión, con las dobles («.fb2.zip» es FB2). null si no tiene extensión. */
        fun formatOf(fileName: String): String? {
            val name = fileName.lowercase()
            if (name.endsWith(".fb2.zip")) return "fb2"
            val dot = name.lastIndexOf('.')
            return if (dot <= 0 || dot == name.lastIndex) null else name.substring(dot + 1)
        }
    }
}
