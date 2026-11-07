package com.linguareader.data.catalog

import com.linguareader.engine.supertonic.HTTP_REDIRECTS
import com.linguareader.engine.supertonic.HttpTransport
import com.linguareader.engine.supertonic.hostOf
import com.linguareader.engine.supertonic.resolveUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSink

/**
 * Catálogo online de libros («Library» en la app): un proveedor posee su API y su identidad estable; se pueden
 * añadir más adaptadores con este contrato. Código común (Android e iOS) sobre [HttpTransport]; antes vivía en la
 * app Android con `HttpURLConnection`.
 */
interface OnlineCatalog {
    val id: String
    val name: String
    suspend fun books(filter: OnlineCatalogFilter, page: Int): CatalogPage
    suspend fun book(id: Int): OnlineBook
    suspend fun categories(): List<CatalogCategory>
    suspend fun languages(): List<CatalogLanguage>
    /** Descarga acotada por HTTPS (portadas): bytes de [url] hasta [limit]. */
    suspend fun fetch(url: String, limit: Long): ByteArray
}

/** Filtro de la búsqueda online (nombre distinto de `core.model.CatalogFilter`, que es el del catálogo local). */
data class OnlineCatalogFilter(val query: String = "", val language: String = "", val category: String = "", val level: String = "")
@Serializable data class CatalogCategory(val slug: String, val name: String)
@Serializable data class CatalogLanguage(val code: String, val name: String)
@Serializable data class CatalogFormat(val kind: String, val url: String)
@Serializable data class CatalogPagination(val page: Int, val pages: Int, val total: Int)
@Serializable data class CatalogPage(val data: List<OnlineBook>, val pagination: CatalogPagination)
@Serializable private data class CatalogResponse<T>(val data: T)
@Serializable data class OnlineBook(
    val id: Int,
    val title: String,
    val author_names: String? = null,
    val languages: String = "en",
    val cover_url: String? = null,
    val r2_key: String? = null,
    val cefr_estimated: String? = null,
    val description: String? = null,
    val source_reading_level: String? = null,
    val source_url: String? = null,
    val formats: List<CatalogFormat> = emptyList(),
) {
    val languageTags: List<String> get() = languages.split(',').map(String::trim).filter(String::isNotEmpty)
    val download: CatalogFormat? get() = formats.firstOrNull { it.kind == "epub" }
        ?: formats.firstOrNull { it.kind == "txt" }
}

class LingoOnlineCatalog(
    private val transport: HttpTransport,
    // Use the deployed Cloudflare origin until the custom domain's DNS delegation is active.
    private val base: String = DEFAULT_BASE,
) : OnlineCatalog {
    override val id = "lingoaudiobook"
    override val name = "LingoAudioBook"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun books(filter: OnlineCatalogFilter, page: Int): CatalogPage =
        json.decodeFromString(get("books?${query(filter, page)}"))
    override suspend fun book(id: Int): OnlineBook = json.decodeFromString<CatalogResponse<OnlineBook>>(get("books/$id")).data
    override suspend fun categories(): List<CatalogCategory> = json.decodeFromString<CatalogResponse<List<CatalogCategory>>>(get("categories")).data
    override suspend fun languages(): List<CatalogLanguage> = json.decodeFromString<CatalogResponse<List<CatalogLanguage>>>(get("languages")).data
    override suspend fun fetch(url: String, limit: Long): ByteArray = CatalogHttp.bytes(transport, url, limit)
    private suspend fun get(path: String) = CatalogHttp.bytes(transport, "$base/$path", 4L * 1024 * 1024).decodeToString()

    companion object {
        const val DEFAULT_BASE = "https://lingoaudiobook.estebandezafra.workers.dev/api/v1"

        fun query(filter: OnlineCatalogFilter, page: Int): String = buildList {
            add("page" to page.coerceAtLeast(1).toString())
            add("limit" to "24")
            add("sort" to "popular")
            if (filter.query.isNotBlank()) add("q" to filter.query.trim())
            if (filter.language.isNotBlank()) add("language" to filter.language)
            if (filter.category.isNotBlank()) add("category" to filter.category)
            if (filter.level.isNotBlank()) add("level" to filter.level)
        }.joinToString("&") { (key, value) -> "$key=${CatalogHttp.percentEncode(value)}" }
    }
}

/** Transferencias HTTPS acotadas, con redirecciones. Sin cookies, credenciales ni datos de la biblioteca del usuario. */
object CatalogHttp {
    /** Comprueba que la URL es https, con host y sin credenciales; devuelve la URL tal cual. */
    fun checkedUrl(value: String): String {
        val trimmed = value.trim()
        require(trimmed.startsWith("https://")) { "Invalid catalog URL" }
        val authority = trimmed.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
        require(authority.isNotEmpty() && '@' !in authority) { "Invalid catalog URL" }
        return trimmed
    }

    suspend fun bytes(transport: HttpTransport, url: String, limit: Long): ByteArray {
        val out = Buffer()
        transfer(transport, url, out, limit)
        return out.readByteArray()
    }

    suspend fun transfer(transport: HttpTransport, url: String, output: BufferedSink, limit: Long) {
        var next = checkedUrl(url)
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val response = transport.get(next, mapOf("User-Agent" to "LingoAudioBook"))
            try {
                val status = response.code
                if (status in HTTP_REDIRECTS) {
                    next = checkedUrl(resolveUrl(next, response.header("Location") ?: error("Missing redirect")))
                } else {
                    check(status in 200..299) { "Catalog HTTP $status" }
                    response.header("Content-Length")?.toLongOrNull()?.let { check(it <= limit) { "Download exceeds limit" } }
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = response.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= limit) { "Download exceeds limit" }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    return
                }
            } finally {
                response.close()
            }
        }
        error("Too many redirects")
    }

    /** Codificación de un valor de consulta (RFC 3986: letras, dígitos, `-_.~` sin tocar; el resto en %XX, UTF-8). */
    fun percentEncode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') append(ch)
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /** Host de una URL del catálogo (para los tests y los mensajes). */
    fun host(url: String): String = hostOf(url)
}
