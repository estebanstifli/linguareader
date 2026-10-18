package com.linguareader.data.content

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.linguareader.core.content.Hashing
import com.linguareader.core.model.CefrLevel
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedDocument
import com.linguareader.importer.ExtractorRegistry
import com.linguareader.importer.ImportException
import com.linguareader.importer.ImportOptions
import com.linguareader.importer.ImportedBookBuilder
import com.linguareader.importer.extract.HtmlExtractor
import com.linguareader.importer.extract.TextExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Documento leído y listo para que el usuario confirme título, idioma y nivel. */
class ImportDraft(
    val bookId: String,
    val sourceName: String,
    val document: ExtractedDocument,
    /** Idioma declarado por el documento, normalizado a BCP-47, si lo hay. */
    val detectedLanguage: String?,
    val alreadyImported: Boolean,
)

/**
 * Importación en dos pasos: `read*` obtiene el texto (fichero elegido, página web o texto
 * compartido) y [commit] lo convierte en libro con las opciones confirmadas y lo guarda.
 * El id deriva del contenido, así que reimportar lo mismo reemplaza en lugar de duplicar.
 */
@Singleton
class BookImportService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val registry: ExtractorRegistry,
    private val repository: ImportedBookRepository,
) {
    private val builder = ImportedBookBuilder()

    /** Fichero elegido con el selector del sistema o recibido con "Abrir con…"/"Compartir". */
    suspend fun read(uri: Uri): ImportDraft = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "documento"
        val input = resolver.openInputStream(uri) ?: throw ImportException(ImportException.Reason.CORRUPT, "No se pudo abrir el fichero")
        withTempCopy(input, MAX_FILE_BYTES) { copy, hash ->
            draft(hash, name, registry.extract(DocumentSource(copy, name, resolver.getType(uri))))
        }
    }

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
            withTempCopy(connection.inputStream, MAX_PAGE_BYTES) { copy, hash ->
                val html = copy.inputStream().use { Jsoup.parse(it, null, connection.url.toString()) }
                val document = HtmlExtractor().fromDocument(html, parsed.host, webPage = true)
                if (document.paragraphCount == 0) {
                    throw ImportException(ImportException.Reason.NO_TEXT, "No se encontró texto legible en la página")
                }
                draft(hash, connection.url.toString(), document)
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
        draft(Hashing.sha256Hex(text), name, document)
    }

    suspend fun commit(draft: ImportDraft, title: String, author: String, language: String, level: CefrLevel): String =
        withContext(Dispatchers.Default) {
            val book = builder.build(
                draft.document,
                ImportOptions(
                    bookId = draft.bookId,
                    title = title.ifBlank { draft.document.title ?: draft.sourceName },
                    authors = author.split(',').map(String::trim).filter(String::isNotEmpty),
                    language = language,
                    level = level,
                    sourceName = draft.sourceName,
                ),
            )
            repository.save(book, draft.document.format)
            draft.bookId
        }

    suspend fun delete(bookId: String) = repository.delete(bookId)

    private suspend fun draft(hash: String, name: String, document: ExtractedDocument): ImportDraft {
        val id = ImportedBookRepository.ID_PREFIX + hash.take(16)
        return ImportDraft(
            bookId = id,
            sourceName = name,
            document = document,
            detectedLanguage = normalizeLanguage(document.language),
            alreadyImported = repository.contains(id),
        )
    }

    /** Copia [input] a la caché calculando su sha256 (con límite de tamaño) y la borra al terminar. */
    private suspend fun <T> withTempCopy(input: InputStream, limit: Long, block: suspend (File, String) -> T): T {
        val dir = File(context.cacheDir, "import").apply { mkdirs() }
        val copy = File(dir, UUID.randomUUID().toString())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            input.use { src ->
                copy.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > limit) {
                            throw ImportException(ImportException.Reason.UNSUPPORTED_FORMAT, "El fichero supera ${limit / 1024 / 1024} MB")
                        }
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
            }
            return block(copy, digest.digest().joinToString("") { "%02x".format(it) })
        } finally {
            copy.delete()
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 200L * 1024 * 1024
        const val MAX_PAGE_BYTES = 10L * 1024 * 1024

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
