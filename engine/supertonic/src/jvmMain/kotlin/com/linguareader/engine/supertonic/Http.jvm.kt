package com.linguareader.engine.supertonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** HttpURLConnection (Android y JVM de escritorio), sin seguir redirecciones. */
actual fun platformHttpTransport(connectTimeoutMs: Int, readTimeoutMs: Int): HttpTransport =
    UrlConnectionTransport(connectTimeoutMs, readTimeoutMs)

class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
    private val open: (java.net.URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        val conn = open(URI(url).toURL())
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = try {
            conn.responseCode
        } catch (e: java.io.IOException) {
            conn.disconnect()
            throw e
        }
        Response(conn, code)
    }

    private class Response(private val conn: HttpURLConnection, override val code: Int) : HttpResponse {
        private val input: InputStream? by lazy {
            runCatching { if (code >= 400) conn.errorStream else conn.inputStream }.getOrNull()
        }

        override fun header(name: String): String? = conn.getHeaderField(name)

        override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
            input?.read(buffer) ?: -1
        }

        override fun close() {
            runCatching { input?.close() }
            conn.disconnect()
        }
    }
}
