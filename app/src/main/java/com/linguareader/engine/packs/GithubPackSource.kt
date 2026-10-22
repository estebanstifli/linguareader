package com.linguareader.engine.packs

import com.linguareader.BuildConfig
import com.linguareader.engine.supertonic.ModelDownloader
import com.linguareader.engine.supertonic.ModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Origen de los paquetes descargables (voces pregeneradas, modelos de alineación, bibliotecas EPUB): los
 * ficheros adjuntos de una release de GitHub (`BuildConfig.PACKS_REPO`, etiqueta `BuildConfig.PACKS_TAG`
 * o la que se indique, p. ej. `BuildConfig.LIBRARY_TAG`).
 * El repositorio es público: no hace falta token.
 * Se resuelven los ids de los ficheros con la API y se descargan con `Accept: application/octet-stream`.
 */
class GithubPackSource() {

    val repo: String = BuildConfig.PACKS_REPO
    val tag: String = BuildConfig.PACKS_TAG
    val configured: Boolean get() = repo.isNotBlank() && tag.isNotBlank()

    /**
     * Descargador para [files]; el fichero local `path` se busca como adjunto `assetName(file)` de la release.
     * @throws IOException si no se puede consultar la release (sin red, sin permiso, etiqueta inexistente).
     */
    suspend fun downloader(files: List<ModelFile>, tag: String = this.tag, assetName: (ModelFile) -> String): ModelDownloader {
        val ids = assetIds(tag)
        return ModelDownloader(
            baseUrl = "",
            files = files,
            urlFor = { f ->
                val id = ids[assetName(f)] ?: throw IOException("El paquete no está en la release $tag: ${assetName(f)}")
                "https://api.github.com/repos/$repo/releases/assets/$id"
            },
            headers = mapOf("Accept" to "application/octet-stream", "X-GitHub-Api-Version" to "2022-11-28"),
        )
    }

    /** Contenido de un adjunto pequeño de texto (un catálogo JSON). */
    suspend fun text(assetName: String, tag: String = this.tag): String = withContext(Dispatchers.IO) {
        val id = assetIds(tag)[assetName] ?: throw IOException("El fichero no está en la release $tag: $assetName")
        val conn = URL("https://api.github.com/repos/$repo/releases/assets/$id").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (conn.responseCode != 200) throw IOException("GitHub respondió HTTP ${conn.responseCode}")
            conn.inputStream.use { it.reader().readText() }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun assetIds(tag: String): Map<String, Long> = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$repo/releases/tags/$tag").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            when (val code = conn.responseCode) {
                200 -> Unit
                401, 403 -> throw IOException("GitHub rechazó el acceso (HTTP $code).")
                404 -> throw IOException("No se encuentra la release $tag de $repo.")
                else -> throw IOException("GitHub respondió HTTP $code")
            }
            val json = Json.parseToJsonElement(conn.inputStream.use { it.reader().readText() }).jsonObject
            json["assets"]!!.jsonArray.associate {
                val o = it.jsonObject
                o["name"]!!.jsonPrimitive.content to o["id"]!!.jsonPrimitive.content.toLong()
            }
        } finally {
            conn.disconnect()
        }
    }
}
