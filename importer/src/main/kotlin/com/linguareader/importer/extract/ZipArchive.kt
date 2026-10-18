package com.linguareader.importer.extract

import com.linguareader.importer.ImportException
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Acceso a un ZIP (EPUB, FB2.ZIP, DOCX, ODT) con límites contra ficheros "bomba". */
internal class ZipArchive(file: File) : Closeable {
    private val zip = ZipFile(file)

    val names: List<String> by lazy { zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList() }

    fun has(path: String): Boolean = zip.getEntry(path) != null

    fun bytes(path: String, limit: Long = MAX_ENTRY_BYTES): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw ImportException(ImportException.Reason.CORRUPT, "Entrada demasiado grande en el ZIP: $path")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    fun text(path: String): String? = bytes(path)?.let(TextDecoding::decode)

    override fun close() = zip.close()

    companion object {
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

        fun isZip(head: ByteArray): Boolean =
            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                head[2] == 3.toByte() && head[3] == 4.toByte()

        /** Resuelve [href] (relativo, quizá con %20 y #fragmento) respecto al directorio de [base]. */
        fun resolve(base: String, href: String): String {
            val clean = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }
                .getOrDefault(href.substringBefore('#'))
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
