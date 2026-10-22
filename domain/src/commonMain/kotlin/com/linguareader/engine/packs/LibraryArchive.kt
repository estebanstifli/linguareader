package com.linguareader.engine.packs

import com.linguareader.engine.supertonic.SupertonicModelDir
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import okio.use

/** Catálogo de bibliotecas (`libraries.json`, adjunto de la release y copia en assets/packs). */
@Serializable
data class LibraryCatalog(val formatVersion: Int = 1, val libraries: Map<String, LibraryInfo> = emptyMap())

@Serializable
data class LibraryInfo(
    val version: Int,
    val asset: String,
    val sizeBytes: Long,
    val sha256: String,
    val books: Int,
    val words: Long = 0,
)

/** `library.json` dentro del zip de una biblioteca (tools/build_library.py). */
@Serializable
data class LibraryManifest(val formatVersion: Int, val language: String, val version: Int, val books: List<LibraryBook>)

@Serializable
data class LibraryBook(
    /** Id estable entre versiones (`lib-<idioma>-<slug>`): conserva progreso y vocabulario al actualizar. */
    val id: String,
    val file: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val level: String,
    val words: Int = 0,
    val sizeBytes: Long,
    val sha256: String,
)

/** Lo instalado de una biblioteca: versión y sha256 de cada libro importado (para no reimportar lo igual). */
@Serializable
data class InstalledLibrary(val version: Int, val books: Map<String, String> = emptyMap())

/**
 * Desempaqueta el zip de una biblioteca en [staging] y comprueba cada EPUB con el sha256 de `library.json`.
 * Rechaza entradas que salgan del directorio (zip slip). Código común (Okio): se prueba sin Android.
 */
object LibraryArchive {
    val json = Json { ignoreUnknownKeys = true }

    fun unpack(zip: Path, staging: Path, fileSystem: FileSystem = FileSystem.SYSTEM): LibraryManifest {
        fileSystem.deleteRecursively(staging, mustExist = false)
        fileSystem.createDirectories(staging)
        val archive = fileSystem.openZip(zip)
        val root = "/".toPath()
        for (entry in archive.listRecursively(root)) {
            val meta = archive.metadataOrNull(entry) ?: continue
            if (meta.isDirectory) continue
            val relative = entry.relativeTo(root)
            if (relative.segments.any { it == ".." } || relative.isAbsolute) throw IOException("Entrada de zip no válida: $entry")
            val target = staging / relative
            target.parent?.let { fileSystem.createDirectories(it) }
            archive.source(entry).use { source -> fileSystem.write(target) { writeAll(source) } }
        }
        val manifestFile = staging / "library.json"
        if (!fileSystem.exists(manifestFile)) throw IOException("La biblioteca no contiene library.json")
        val manifest = json.decodeFromString<LibraryManifest>(fileSystem.read(manifestFile) { readUtf8() })
        for (book in manifest.books) {
            val relative = book.file.toPath()
            if (relative.isAbsolute || relative.segments.any { it == ".." }) throw IOException("Ruta no válida en library.json: ${book.file}")
            val f = staging / relative
            val size = fileSystem.metadataOrNull(f)?.takeIf { it.isRegularFile }?.size
            if (size != book.sizeBytes || SupertonicModelDir.sha256(f, fileSystem) != book.sha256) {
                throw IOException("Libro corrupto en la biblioteca: ${book.file}")
            }
        }
        return manifest
    }
}
