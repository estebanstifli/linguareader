package com.linguareader.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** A provider owns its API and stable identity; more catalog adapters can implement this contract. */
interface OnlineCatalog {
    val id: String
    val name: String
    suspend fun books(filter: CatalogFilter, page: Int): CatalogPage
    suspend fun book(id: Int): OnlineBook
    suspend fun categories(): List<CatalogCategory>
    suspend fun languages(): List<CatalogLanguage>
}

data class CatalogFilter(val query: String = "", val language: String = "", val category: String = "", val level: String = "")
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

class LingoOnlineCatalog : OnlineCatalog {
    override val id = "lingoaudiobook"
    override val name = "LingoAudioBook"
    // Use the deployed Cloudflare origin until the custom domain's DNS delegation is active.
    private val base = "https://lingoaudiobook.estebandezafra.workers.dev/api/v1"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun books(filter: CatalogFilter, page: Int): CatalogPage =
        json.decodeFromString(get("books?${query(filter, page)}"))
    override suspend fun book(id: Int): OnlineBook = json.decodeFromString<CatalogResponse<OnlineBook>>(get("books/$id")).data
    override suspend fun categories(): List<CatalogCategory> = json.decodeFromString<CatalogResponse<List<CatalogCategory>>>(get("categories")).data
    override suspend fun languages(): List<CatalogLanguage> = json.decodeFromString<CatalogResponse<List<CatalogLanguage>>>(get("languages")).data
    private suspend fun get(path: String) = CatalogHttp.bytes("$base/$path", 4L * 1024 * 1024).toString(Charsets.UTF_8)

    companion object {
        fun query(filter: CatalogFilter, page: Int): String = buildList {
            add("page" to page.coerceAtLeast(1).toString())
            add("limit" to "24")
            add("sort" to "popular")
            if (filter.query.isNotBlank()) add("q" to filter.query.trim())
            if (filter.language.isNotBlank()) add("language" to filter.language)
            if (filter.category.isNotBlank()) add("category" to filter.category)
            if (filter.level.isNotBlank()) add("level" to filter.level)
        }.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    }
}

/** Bounded HTTPS transfers, including redirects. No cookies, credentials or user library data. */
internal object CatalogHttp {
    fun checkedUrl(value: String): URL {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null) { "Invalid catalog URL" }
        return uri.toURL()
    }

    suspend fun bytes(url: String, limit: Long): ByteArray = ByteArrayOutputStream().use { out ->
        transfer(url, out, limit)
        out.toByteArray()
    }

    suspend fun transfer(url: String, output: OutputStream, limit: Long) = withContext(Dispatchers.IO) {
        var next = checkedUrl(url)
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val connection = (next.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "LingoAudioBook Android")
            }
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    next = checkedUrl(URL(next, connection.getHeaderField("Location") ?: error("Missing redirect")).toString())
                } else {
                    check(status in 200..299) { "Catalog HTTP $status" }
                    check(connection.contentLengthLong <= limit) { "Download exceeds limit" }
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= limit) { "Download exceeds limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                    return@withContext
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }
}
