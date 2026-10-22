package com.linguareader.data.content

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.linguareader.R
import com.linguareader.core.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Genera un TXT del libro (título, autoría, capítulos y texto) para compartirlo. Sirve para cualquier libro,
 * incluido o importado: la app no guarda el archivo original importado, solo su contenido normalizado.
 */
class BookExporter(private val context: Context) {

    suspend fun exportText(book: Book): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, SHARED_DIR).apply { deleteRecursively(); mkdirs() }
        val name = book.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifEmpty { context.getString(R.string.export_default_name) }.take(80)
        val file = File(dir, "$name.txt")
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine(book.title)
            if (book.authors.isNotEmpty()) out.appendLine(book.authors.joinToString())
            out.appendLine()
            for (chapter in book.chapters) {
                if (chapter.title.isNotBlank()) {
                    out.appendLine(chapter.title)
                    out.appendLine()
                }
                for (passage in chapter.passages) {
                    out.appendLine(passage.text)
                    out.appendLine()
                }
            }
            out.appendLine("—")
            out.appendLine(book.sourceAttribution)
            out.appendLine(context.getString(R.string.export_license, book.license.name ?: book.license.id))
        }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    companion object {
        const val SHARED_DIR = "shared"
    }
}
