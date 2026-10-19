package com.linguareader.engine.packs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

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
 * Rechaza entradas que salgan del directorio (zip slip). JVM puro: se prueba sin Android.
 */
object LibraryArchive {
    val json = Json { ignoreUnknownKeys = true }

    fun unpack(zip: File, staging: File): LibraryManifest {
        staging.deleteRecursively()
        staging.mkdirs()
        val root = staging.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val target = File(staging, e.name)
                if (!target.canonicalPath.startsWith(root)) throw IOException("Entrada de zip no válida: ${e.name}")
                target.parentFile?.mkdirs()
                target.outputStream().use { z.copyTo(it) }
            }
        }
        val manifestFile = File(staging, "library.json")
        if (!manifestFile.isFile) throw IOException("La biblioteca no contiene library.json")
        val manifest = json.decodeFromString<LibraryManifest>(manifestFile.readText())
        for (book in manifest.books) {
            val f = File(staging, book.file)
            if (!f.canonicalPath.startsWith(root)) throw IOException("Ruta no válida en library.json: ${book.file}")
            if (!f.isFile || f.length() != book.sizeBytes || sha256(f) != book.sha256) {
                throw IOException("Libro corrupto en la biblioteca: ${book.file}")
            }
        }
        return manifest
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val b = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
