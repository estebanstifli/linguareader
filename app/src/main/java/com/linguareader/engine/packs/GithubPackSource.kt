package com.linguareader.engine.packs

import com.linguareader.BuildConfig
import com.linguareader.data.prefs.UserPreferencesRepository
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Origen de los paquetes descargables (voces pregeneradas, modelos de alineación): los ficheros
 * adjuntos de una release de GitHub (`BuildConfig.PACKS_REPO`, etiqueta `BuildConfig.PACKS_TAG`).
 * Si el repositorio es privado hace falta un token (ajustes → «Descarga de paquetes»); nunca va en el APK.
 * Se resuelven los ids de los ficheros con la API y se descargan con `Accept: application/octet-stream`.
 */
@Singleton
class GithubPackSource @Inject constructor(private val prefs: UserPreferencesRepository) {

    val repo: String = BuildConfig.PACKS_REPO
    val tag: String = BuildConfig.PACKS_TAG
    val configured: Boolean get() = repo.isNotBlank() && tag.isNotBlank()

    /**
     * Descargador para [files]; el fichero local `path` se busca como adjunto `assetName(file)` de la release.
     * @throws IOException si no se puede consultar la release (sin red, sin permiso, etiqueta inexistente).
     */
    suspend fun downloader(files: List<ModelFile>, assetName: (ModelFile) -> String): ModelDownloader {
        val token = prefs.packsToken.first()
        val ids = assetIds(token)
        val auth = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
        return ModelDownloader(
            baseUrl = "",
            files = files,
            urlFor = { f ->
                val id = ids[assetName(f)] ?: throw IOException("El paquete no está en la release $tag: ${assetName(f)}")
                "https://api.github.com/repos/$repo/releases/assets/$id"
            },
            headers = auth + mapOf("Accept" to "application/octet-stream", "X-GitHub-Api-Version" to "2022-11-28"),
        )
    }

    private suspend fun assetIds(token: String): Map<String, Long> = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$repo/releases/tags/$tag").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            when (val code = conn.responseCode) {
                200 -> Unit
                401, 403 -> throw IOException("GitHub rechazó el acceso (HTTP $code): revisa el token de «Descarga de paquetes».")
                404 -> throw IOException("No se encuentra la release $tag de $repo. Si el repositorio es privado, introduce un token con acceso de lectura.")
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
