package com.linguareader.importer.extract

import com.linguareader.core.platform.percentDecode
import com.linguareader.importer.ImportException
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import okio.use

/** Acceso a un ZIP (EPUB, FB2.ZIP, DOCX, ODT) con límites contra ficheros "bomba". */
internal class ZipArchive(fileSystem: FileSystem, path: Path) : AutoCloseable {
    private val zip: FileSystem = fileSystem.openZip(path)

    val names: List<String> by lazy {
        zip.listRecursively(ROOT)
            .filter { zip.metadataOrNull(it)?.isRegularFile == true }
            .map { it.toString().removePrefix("/") }
            .toList()
    }

    private fun entry(path: String): Path = (ROOT.toString() + path.removePrefix("/")).toPath()

    fun has(path: String): Boolean = zip.metadataOrNull(entry(path))?.isRegularFile == true

    fun bytes(path: String, limit: Long = MAX_ENTRY_BYTES): ByteArray? {
        if (!has(path)) return null
        return zip.source(entry(path)).use { source ->
            val out = Buffer()
            var total = 0L
            while (true) {
                val n = source.read(out, 64L * 1024)
                if (n < 0) break
                total += n
                if (total > limit) throw ImportException(ImportException.Reason.CORRUPT, "Entrada demasiado grande en el ZIP: $path")
            }
            out.readByteArray()
        }
    }

    /** Primeros [maxBytes] bytes de una entrada (cabeceras), sin leerla entera. */
    fun head(path: String, maxBytes: Int): ByteArray? {
        if (!has(path)) return null
        return zip.source(entry(path)).use { source ->
            val out = Buffer()
            var total = 0L
            while (total < maxBytes) {
                val n = source.read(out, maxBytes - total)
                if (n < 0) break
                total += n
            }
            out.readByteArray()
        }
    }

    fun text(path: String): String? = bytes(path)?.let(TextDecoding::decode)

    /** El ZIP de Okio abre y cierra el fichero en cada lectura: no queda nada que liberar. */
    override fun close() = Unit

    companion object {
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        private val ROOT = "/".toPath()

        fun isZip(head: ByteArray): Boolean =
            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                head[2] == 3.toByte() && head[3] == 4.toByte()

        /** Resuelve [href] (relativo, quizá con %20 y #fragmento) respecto al directorio de [base]. */
        fun resolve(base: String, href: String): String {
            val clean = href.substringBefore('#').percentDecode()
            val parts = if (clean.startsWith("/")) mutableListOf() else base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
            clean.split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += part
                }
            }
            return parts.joinToString("/")
        }
    }
}
