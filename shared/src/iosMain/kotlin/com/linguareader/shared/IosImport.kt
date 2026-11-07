package com.linguareader.shared

import com.linguareader.core.model.CefrLevel
import com.linguareader.data.content.ImportDraft
import com.linguareader.data.content.LocalBookImporter
import com.linguareader.engine.supertonic.HTTP_REDIRECTS
import com.linguareader.engine.supertonic.hostOf
import com.linguareader.engine.supertonic.platformHttpTransport
import com.linguareader.engine.supertonic.resolveUrl
import com.linguareader.importer.ImportException
import com.linguareader.importer.extract.HtmlExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.IOException
import okio.Path.Companion.toPath

/**
 * Resultado de leer un documento para importarlo, como lo ve Swift: un borrador para revisar o un fallo tipado
 * (las excepciones de Kotlin llegan a Swift como NSError opacos). Misma clasificación que `ImportUiState.Failed`
 * en Android: [reason] se traduce en la app; [detail] es el mensaje técnico del importador.
 */
sealed interface ImportOutcome {
    // Interfaz sellada y no clase: así Swift ve `ImportOutcomeDraft` / `ImportOutcomeFailed` (nombres planos).
    class Draft(val draft: ImportDraft) : ImportOutcome
    class Failed(val reason: ImportException.Reason?, val detail: String?) : ImportOutcome
}

/**
 * Fuentes de importación de iOS sobre el importador compartido ([LocalBookImporter]): fichero ya copiado a la
 * caché por Swift (selector de archivos, «Abrir en…»), página web (única operación con red, siempre iniciada por
 * el usuario; las imágenes remotas no se descargan) y texto pegado. Equivale a `BookImportService` en Android.
 */
internal class IosImportService(private val importer: LocalBookImporter) {

    suspend fun readDocument(path: String, displayName: String, mimeType: String?): ImportOutcome = outcome {
        importer.readFile(path.toPath(), displayName, mimeType)
    }

    suspend fun readText(text: String, title: String?): ImportOutcome = outcome { importer.readText(text, title) }

    suspend fun readWebPage(url: String): ImportOutcome = outcome {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw ImportException(ImportException.Reason.UNSUPPORTED_FORMAT, "Not a valid address: $trimmed")
        }
        val (finalUrl, bytes) = withContext(Dispatchers.IO) { download(trimmed) }
        importer.withTempCopy(Buffer().write(bytes), LocalBookImporter.MAX_PAGE_BYTES) { _, hash ->
            val document = HtmlExtractor().fromWebPage(bytes, finalUrl, hostOf(finalUrl))
            if (document.paragraphCount == 0) throw ImportException(ImportException.Reason.NO_TEXT, "No readable text was found on the page")
            importer.draft(hash, finalUrl, document)
        }
    }

    /** GET siguiendo redirecciones a mano (como `getText`), con tope de tamaño y comprobando que sea HTML. */
    private suspend fun download(url: String): Pair<String, ByteArray> {
        val transport = platformHttpTransport(connectTimeoutMs = 15_000, readTimeoutMs = 20_000)
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (iPhone) LinguaReader", "Accept" to "text/html,application/xhtml+xml")
        var current = url
        try {
            repeat(6) {
                val response = transport.get(current, headers)
                try {
                    if (response.code in HTTP_REDIRECTS) {
                        val location = response.header("Location") ?: throw ImportException(ImportException.Reason.CORRUPT, "The page responded ${response.code} without Location")
                        current = resolveUrl(current, location)
                        return@repeat
                    }
                    if (response.code !in 200..299) throw ImportException(ImportException.Reason.CORRUPT, "The page responded ${response.code}")
                    val type = response.header("Content-Type").orEmpty()
                    if (type.isNotEmpty() && "html" !in type && "xml" !in type) {
                        throw ImportException(ImportException.Reason.UNSUPPORTED_FORMAT, "The link is not a web page ($type)")
                    }
                    val out = Buffer()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = response.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        if (out.size > LocalBookImporter.MAX_PAGE_BYTES) throw ImportException(ImportException.Reason.UNSUPPORTED_FORMAT, "The page is larger than ${LocalBookImporter.MAX_PAGE_BYTES / 1024 / 1024} MB")
                    }
                    return current to out.readByteArray()
                } finally {
                    response.close()
                }
            }
        } catch (e: IOException) {
            throw ImportException(ImportException.Reason.CORRUPT, "The page could not be downloaded: ${e.message}", e)
        }
        throw ImportException(ImportException.Reason.CORRUPT, "Too many redirects")
    }

    private suspend inline fun outcome(block: () -> ImportDraft): ImportOutcome = try {
        ImportOutcome.Draft(block())
    } catch (e: ImportException) {
        ImportOutcome.Failed(e.reason, e.message?.takeIf { e.reason != ImportException.Reason.NO_TEXT })
    } catch (e: Exception) {
        ImportOutcome.Failed(null, e.message)
    }

    companion object {
        /** Idiomas ofrecidos al importar (la voz del sistema suele tener voz para todos), como en Android. */
        val COMMON_LANGUAGES = listOf("en", "fr", "es", "de", "it", "pt", "nl", "ca")
    }
}
