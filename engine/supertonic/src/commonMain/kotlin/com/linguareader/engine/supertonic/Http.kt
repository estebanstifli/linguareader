package com.linguareader.engine.supertonic

import okio.IOException

/**
 * Respuesta HTTP en streaming. [read] entrega el cuerpo por bloques (−1 al final) sin cargarlo entero en
 * memoria: los modelos pesan cientos de MB.
 */
interface HttpResponse {
    val code: Int
    /** Cabecera de la respuesta (nombre sin distinguir mayúsculas) o null. */
    fun header(name: String): String?
    suspend fun read(buffer: ByteArray): Int
    fun close()
}

/**
 * Puerto HTTP mínimo de los módulos compartidos: GET con cabeceras, **sin seguir redirecciones** (el descargador
 * las sigue a mano para no reenviar cabeceras de autenticación a otro host y conservar `Range`).
 * JVM/Android: `HttpURLConnection`; iOS: `NSURLSession`.
 */
interface HttpTransport {
    /** @throws IOException si no se pudo conectar. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

expect fun platformHttpTransport(connectTimeoutMs: Int = 20_000, readTimeoutMs: Int = 30_000): HttpTransport

/** Códigos de redirección que se siguen a mano. */
val HTTP_REDIRECTS: Set<Int> = setOf(301, 302, 303, 307, 308)

/** Host (con puerto) de una URL absoluta, en minúsculas. */
fun hostOf(url: String): String = url.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase()

/** Resuelve `Location` relativo a [base] (absoluto, `//host/...`, `/ruta` o relativo). */
fun resolveUrl(base: String, location: String): String = when {
    "://" in location -> location
    location.startsWith("//") -> base.substringBefore("://") + ":" + location
    location.startsWith("/") -> base.substringBefore("://") + "://" + hostOf(base) + location
    else -> base.substringBefore('?').substringBeforeLast('/') + "/" + location
}

/**
 * GET que sigue redirecciones (hasta [maxRedirects]) y devuelve el cuerpo como texto UTF-8. Las cabeceras se
 * descartan al saltar a otro host.
 * @throws IOException con el código HTTP si la respuesta no es 2xx.
 */
suspend fun HttpTransport.getText(url: String, headers: Map<String, String> = emptyMap(), maxRedirects: Int = 6): String {
    var current = url
    var hdrs = headers
    repeat(maxRedirects) {
        val response = get(current, hdrs)
        try {
            if (response.code in HTTP_REDIRECTS) {
                val location = response.header("Location") ?: throw IOException("Redirección sin destino (HTTP ${response.code})")
                val next = resolveUrl(current, location)
                if (hostOf(next) != hostOf(current)) hdrs = emptyMap()
                current = next
            } else {
                if (response.code !in 200..299) throw HttpStatusException(response.code, current)
                val out = okio.Buffer()
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = response.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                return out.readUtf8()
            }
        } finally {
            response.close()
        }
    }
    throw IOException("Demasiadas redirecciones al pedir $url")
}

class HttpStatusException(val code: Int, val url: String) : IOException("HTTP $code al pedir $url")
