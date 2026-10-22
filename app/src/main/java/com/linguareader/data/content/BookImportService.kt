package com.linguareader.data.content

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fleeksoft.ksoup.Ksoup
import com.linguareader.core.content.Hashing
import com.linguareader.core.model.CefrLevel
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractorRegistry
import com.linguareader.importer.ImportException
import com.linguareader.importer.extract.HtmlExtractor
import com.linguareader.importer.extract.TextDecoding
import com.linguareader.importer.extract.TextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.source
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Fuentes de importación propias de Android (selector de ficheros, «Abrir con…»/«Compartir», página web, texto
 * compartido) sobre el importador compartido ([LocalBookImporter]), que hace el trabajo común: copia temporal con
 * hash, extracción, construcción del libro y guardado. Al confirmar un PDF también lanza el cálculo de las cajas de
 * palabra para «Ver original».
 */
class BookImportService(
    private val context: Context,
    private val registry: ExtractorRegistry,
    private val local: LocalBookImporter,
    private val repository: ImportedBookRepository,
    private val pdfLayouts: PdfTextLayouts,
) {
    /** Fichero elegido con el selector del sistema o recibido con "Abrir con…"/"Compartir". */
    suspend fun read(uri: Uri): ImportDraft = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "documento"
        val input = resolver.openInputStream(uri) ?: throw ImportException(ImportException.Reason.CORRUPT, "No se pudo abrir el fichero")
        local.withTempCopy(input.source(), LocalBookImporter.MAX_FILE_BYTES) { copy, hash ->
            val document = registry.extract(DocumentSource(copy, name, resolver.getType(uri)))
            val original = OriginalDocument(uri.toString(), document.format, document.pageCount)
                .takeIf { it.viewable }
                ?.also { keepAccess(uri) }
            local.draft(hash, name, document).let { if (original != null) it.copy(original = original) else it }
        }
    }

    /** true si [uri] es un PDF (por su firma), aunque no se pueda importar (escaneado, sin texto). */
    suspend fun isPdf(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(1024)
                val n = input.read(head)
                n > 0 && String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * Conserva el permiso de lectura tras reiniciar el móvil, si el proveedor lo concede (selector de
     * ficheros, carpetas elegidas). «Abrir con…» desde otras apps no suele concederlo: el visor pedirá
     * volver a localizar el fichero cuando lo pierda.
     */
    fun keepAccess(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    /** Fichero local de la app (EPUB de una biblioteca descargada). */
    suspend fun readFile(file: File): ImportDraft = local.readFile(file.toOkioPath(), file.name)

    /** Página web: única operación con red, siempre iniciada por el usuario. Las imágenes remotas no se descargan. */
    suspend fun readUrl(url: String): ImportDraft = withContext(Dispatchers.IO) {
        val parsed = runCatching { URL(url) }.getOrNull()
        if (parsed == null || parsed.protocol !in setOf("http", "https")) {
            throw ImportException(ImportException.Reason.UNSUPPORTED_FORMAT, "Dirección no válida: $url")
        }
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LinguaReader")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw ImportException(ImportException.Reason.CORRUPT, "La página respondió ${connection.responseCode}")
            }
            val type = connection.contentType.orEmpty()
            if ("html" !in type && type.isNotEmpty()) {
                throw ImportException(ImportException.Reason.UNSUPPORTED_FORMAT, "El enlace no es una página web ($type)")
            }
            local.withTempCopy(connection.inputStream.source(), LocalBookImporter.MAX_PAGE_BYTES) { copy, hash ->
                val bytes = FileSystem.SYSTEM.read(copy) { readByteArray() }
                val html = Ksoup.parse(TextDecoding.decodeMarkup(bytes), connection.url.toString())
                val document = HtmlExtractor().fromDocument(html, parsed.host, webPage = true)
                if (document.paragraphCount == 0) {
                    throw ImportException(ImportException.Reason.NO_TEXT, "No se encontró texto legible en la página")
                }
                local.draft(hash, connection.url.toString(), document)
            }
        } catch (e: java.io.IOException) {
            throw ImportException(ImportException.Reason.CORRUPT, "No se pudo descargar la página: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /** Texto compartido desde otra app (un artículo copiado, un correo...). */
    suspend fun readText(text: String, title: String?): ImportDraft = withContext(Dispatchers.Default) {
        val name = title?.takeIf { it.isNotBlank() } ?: text.trim().lineSequence().first().take(60)
        val document = TextExtractor().parse(text, name)
        if (document.paragraphCount == 0) throw ImportException(ImportException.Reason.NO_TEXT, "El texto compartido está vacío")
        local.draft(Hashing.sha256Hex(text), name, document)
    }

    /**
     * @param stableId id fijo (sin prefijo) en lugar del derivado del contenido: los libros de una
     *   biblioteca conservan progreso, marcadores y vocabulario cuando se actualiza su texto.
     */
    suspend fun commit(
        draft: ImportDraft,
        title: String,
        author: String,
        language: String,
        level: CefrLevel,
        stableId: String? = null,
    ): String {
        val bookId = local.commit(draft, title, author, language, level, stableId)
        // Posiciones de las palabras sobre el PDF (voz y traducción en «Ver original»), en segundo plano ya.
        if (draft.original != null) {
            pdfLayouts.invalidate(bookId)
            pdfLayouts.ensure(bookId)
        }
        return bookId
    }

    suspend fun delete(bookId: String) {
        val original = repository.original(bookId)
        local.delete(bookId)
        // Los permisos persistentes son limitados (128–512 por app): se devuelve el que ya no hace falta.
        original?.uri?.let(Uri::parse)?.takeIf { it.scheme == "content" }?.let { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    companion object {
        const val MAX_FILE_BYTES = LocalBookImporter.MAX_FILE_BYTES
        const val MAX_PAGE_BYTES = LocalBookImporter.MAX_PAGE_BYTES

        /** "en_US", "ENG", "fr-fr" → "en-US", "en", "fr-FR"; null si no es un idioma reconocible. */
        fun normalizeLanguage(tag: String?): String? {
            val locale = Locale.forLanguageTag(tag?.trim()?.replace('_', '-') ?: return null)
            val language = locale.language.takeIf { it.isNotEmpty() && it != "und" } ?: return null
            // ISO 639-2 de tres letras con equivalente de dos ("eng" → "en").
            val primary = if (language.length == 3) {
                Locale.getAvailableLocales().firstOrNull { runCatching { it.isO3Language == language }.getOrDefault(false) }?.language ?: language
            } else {
                language
            }
            return if (locale.country.isNotEmpty()) "$primary-${locale.country}" else primary
        }
    }
}
