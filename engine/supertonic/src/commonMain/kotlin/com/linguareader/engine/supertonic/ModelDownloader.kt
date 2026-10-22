package com.linguareader.engine.supertonic

import com.linguareader.core.platform.Sha256
import com.linguareader.core.platform.ioDispatcher
import com.linguareader.core.platform.toHexLower
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use
import kotlin.coroutines.coroutineContext

/**
 * Descarga los ficheros de un modelo con reanudación (cabecera Range), verificación sha256 de
 * cada fichero y publicación atómica (`.part` → nombre final). Cancelable entre bloques.
 * Código común: lo usan la app Android, la app iOS y la prueba funcional en el PC a través del puerto [HttpTransport].
 */
class ModelDownloader(
    private val baseUrl: String,
    private val files: List<ModelFile>,
    private val transport: HttpTransport = platformHttpTransport(),
    /** URL de cada fichero; por defecto `<baseUrl>/<path>`. */
    private val urlFor: (ModelFile) -> String = { "$baseUrl/${it.path}" },
    /** Cabeceras de la primera petición (p. ej. autenticación). Se descartan al redirigir a otro host. */
    private val headers: Map<String, String> = emptyMap(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : FileDownloader {

    override suspend fun download(targetDir: Path, onProgress: (DownloadProgress) -> Unit): DownloadResult = withContext(ioDispatcher) {
        fileSystem.createDirectories(targetDir)
        val total = files.sumOf { it.sizeBytes }
        var completedBytes = 0L
        try {
            for (mf in files) {
                val target = targetDir / mf.path
                if (sizeOf(target) == mf.sizeBytes && SupertonicModelDir.sha256(target, fileSystem) == mf.sha256) {
                    completedBytes += mf.sizeBytes
                    onProgress(DownloadProgress(completedBytes, total, mf.path))
                    continue
                }
                target.parent?.let { fileSystem.createDirectories(it) }
                downloadOne(mf, target) { fileBytes -> onProgress(DownloadProgress(completedBytes + fileBytes, total, mf.path)) }
                completedBytes += mf.sizeBytes
            }
            fileSystem.write(targetDir / SupertonicModel.MARKER) { writeUtf8(SupertonicModel.REVISION) }
            DownloadResult.Completed
        } catch (e: IOException) {
            DownloadResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun sizeOf(path: Path): Long = fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size ?: 0L

    private suspend fun downloadOne(mf: ModelFile, target: Path, onBytes: (Long) -> Unit) {
        val part = target.parent!! / (target.name + ".part")
        if (sizeOf(part) > mf.sizeBytes) fileSystem.delete(part)
        repeat(2) { attempt ->
            var digest = Sha256()
            var have = sizeOf(part)
            if (have > 0) fileSystem.read(part) { updateDigest(digest, this) }

            val response = connect(urlFor(mf), have)
            try {
                val code = response.code
                val append = when {
                    code == 206 && have > 0 -> true
                    code == 200 -> {
                        // El servidor ignoró Range: empezar de cero.
                        have = 0
                        digest = Sha256()
                        false
                    }
                    code == 416 && have == mf.sizeBytes -> null // ya estaba completo
                    else -> throw IOException("HTTP $code al descargar ${mf.path}")
                }
                if (append != null) {
                    val sink = if (append) fileSystem.appendingSink(part) else fileSystem.sink(part)
                    sink.buffer().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = response.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            have += n
                            onBytes(have)
                        }
                    }
                }
            } finally {
                response.close()
            }

            val sha = if (sizeOf(part) == mf.sizeBytes && have == mf.sizeBytes) digest.digest().toHexLower()
            else SupertonicModelDir.sha256(part, fileSystem)
            if (sizeOf(part) == mf.sizeBytes && sha == mf.sha256) {
                fileSystem.delete(target, mustExist = false)
                fileSystem.atomicMove(part, target)
                return
            }
            // Contenido corrupto o incompleto: un reintento desde cero.
            fileSystem.delete(part, mustExist = false)
            if (attempt == 1) throw IOException("Integridad fallida en ${mf.path} (sha256 distinto)")
        }
    }

    /**
     * Abre la conexión siguiendo redirecciones a mano: la API de GitHub redirige a una URL firmada de
     * otro host, que rechaza la cabecera Authorization. Range se mantiene en todos los saltos.
     */
    private suspend fun connect(first: String, have: Long): HttpResponse {
        var url = first
        var hdrs = headers
        repeat(6) {
            val request = if (have > 0) hdrs + ("Range" to "bytes=$have-") else hdrs
            val response = transport.get(url, request)
            if (response.code !in HTTP_REDIRECTS) return response
            val location = response.header("Location")
            response.close()
            if (location == null) throw IOException("Redirección sin destino (HTTP ${response.code})")
            val next = resolveUrl(url, location)
            if (hostOf(next) != hostOf(url)) hdrs = emptyMap()
            url = next
        }
        throw IOException("Demasiadas redirecciones al descargar $first")
    }

    private fun updateDigest(md: Sha256, source: okio.BufferedSource) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
}
