package com.linguareader.engine.supertonic

import okio.Path

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
 * Puerto de descarga de ficheros de modelo con reanudación y verificación sha256. En JVM/Android lo implementa
 * [ModelDownloader] (HttpURLConnection); en iOS lo hará un descargador con `URLSession` en segundo plano, porque
 * una descarga de cientos de MB en primer plano se corta al suspender la app.
 */
interface FileDownloader {
    suspend fun download(targetDir: Path, onProgress: (DownloadProgress) -> Unit): DownloadResult
}
