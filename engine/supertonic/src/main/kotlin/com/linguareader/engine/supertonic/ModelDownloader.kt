package com.linguareader.engine.supertonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val currentFile: String,
) {
    val fraction: Float get() = if (totalBytes == 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

sealed interface DownloadResult {
    data object Completed : DownloadResult
    data class Failed(val message: String) : DownloadResult
}

/**
 * Descarga los ficheros de un modelo con reanudación (cabecera Range), verificación sha256 de
 * cada fichero y publicación atómica (`.part` → nombre final). Cancelable entre bloques.
 * Es JVM puro: lo usan la app Android y la prueba funcional en el PC.
 */
class ModelDownloader(
    private val baseUrl: String,
    private val files: List<ModelFile>,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    /** URL de cada fichero; por defecto `<baseUrl>/<path>`. */
    private val urlFor: (ModelFile) -> String = { "$baseUrl/${it.path}" },
    /** Cabeceras de la primera petición (p. ej. autenticación). Se descartan al redirigir a otro host. */
    private val headers: Map<String, String> = emptyMap(),
) {

    suspend fun download(targetDir: File, onProgress: (DownloadProgress) -> Unit): DownloadResult = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val total = files.sumOf { it.sizeBytes }
        var completedBytes = 0L
        try {
            for (mf in files) {
                val target = File(targetDir, mf.path)
                if (target.length() == mf.sizeBytes && SupertonicModelDir.sha256(target) == mf.sha256) {
                    completedBytes += mf.sizeBytes
                    onProgress(DownloadProgress(completedBytes, total, mf.path))
                    continue
                }
                target.parentFile?.mkdirs()
                downloadOne(mf, target) { fileBytes -> onProgress(DownloadProgress(completedBytes + fileBytes, total, mf.path)) }
                completedBytes += mf.sizeBytes
            }
            File(targetDir, SupertonicModel.MARKER).writeText(SupertonicModel.REVISION)
            DownloadResult.Completed
        } catch (e: IOException) {
            DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun downloadOne(mf: ModelFile, target: File, onBytes: (Long) -> Unit) {
        val part = File(target.path + ".part")
        if (part.length() > mf.sizeBytes) part.delete()
        repeat(2) { attempt ->
            val digest = MessageDigest.getInstance("SHA-256")
            var have = part.length()
            if (have > 0) part.inputStream().use { input -> updateDigest(digest, input) }

            val conn = connect(urlFor(mf), have)
            try {
                val code = conn.responseCode
                val append = when {
                    code == HttpURLConnection.HTTP_PARTIAL && have > 0 -> true
                    code == HttpURLConnection.HTTP_OK -> {
                        // El servidor ignoró Range: empezar de cero.
                        have = 0
                        digest.reset()
                        false
                    }
                    code == 416 && have == mf.sizeBytes -> null // ya estaba completo
                    else -> throw IOException("HTTP $code al descargar ${mf.path}")
                }
                if (append != null) {
                    conn.inputStream.use { input ->
                        FileOutputStream(part, append).use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                have += n
                                onBytes(have)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            val sha = if (part.length() == mf.sizeBytes && have == mf.sizeBytes) {
                digest.digest().joinToString("") { "%02x".format(it) }
            } else {
                SupertonicModelDir.sha256(part)
            }
            if (part.length() == mf.sizeBytes && sha == mf.sha256) {
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("No se pudo mover ${part.name}")
                return
            }
            // Contenido corrupto o incompleto: un reintento desde cero.
            part.delete()
            if (attempt == 1) throw IOException("Integridad fallida en ${mf.path} (sha256 distinto)")
        }
    }

    /**
     * Abre la conexión siguiendo redirecciones a mano: la API de GitHub redirige a una URL firmada de
     * otro host, que rechaza la cabecera Authorization. Range se mantiene en todos los saltos.
     */
    private fun connect(first: String, have: Long): HttpURLConnection {
        var url = java.net.URI(first).toURL()
        var hdrs = headers
        repeat(6) {
            val conn = open(url)
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            hdrs.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code !in REDIRECTS) return conn
            val location = conn.getHeaderField("Location") ?: run { conn.disconnect(); throw IOException("Redirección sin destino (HTTP $code)") }
            conn.disconnect()
            val next = URL(url, location)
            if (next.host != url.host) hdrs = emptyMap()
            url = next
        }
        throw IOException("Demasiadas redirecciones al descargar $first")
    }

    private fun updateDigest(md: MessageDigest, input: java.io.InputStream) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
