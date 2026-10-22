package com.linguareader.engine.packs

import com.linguareader.engine.supertonic.HttpStatusException
import com.linguareader.engine.supertonic.HttpTransport
import com.linguareader.engine.supertonic.ModelDownloader
import com.linguareader.engine.supertonic.ModelFile
import com.linguareader.engine.supertonic.getText
import com.linguareader.engine.supertonic.platformHttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.IOException

/**
 * Origen de los paquetes descargables (voces pregeneradas, modelos de alineación, bibliotecas EPUB): los
 * ficheros adjuntos de una release de GitHub ([repo], etiqueta [tag] o la que se indique).
 * El repositorio es público: no hace falta token.
 * Se resuelven los ids de los ficheros con la API y se descargan con `Accept: application/octet-stream`.
 */
class GithubPackSource(
    val repo: String,
    val tag: String,
    private val transport: HttpTransport = platformHttpTransport(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
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
            transport = transport,
            urlFor = { f ->
                val id = ids[assetName(f)] ?: throw IOException("El paquete no está en la release $tag: ${assetName(f)}")
                "https://api.github.com/repos/$repo/releases/assets/$id"
            },
            headers = ASSET_HEADERS,
            fileSystem = fileSystem,
        )
    }

    /** Contenido de un adjunto pequeño de texto (un catálogo JSON). */
    suspend fun text(assetName: String, tag: String = this.tag): String {
        val id = assetIds(tag)[assetName] ?: throw IOException("El fichero no está en la release $tag: $assetName")
        return try {
            transport.getText("https://api.github.com/repos/$repo/releases/assets/$id", ASSET_HEADERS)
        } catch (e: HttpStatusException) {
            throw IOException("GitHub respondió HTTP ${e.code}")
        }
    }

    private suspend fun assetIds(tag: String): Map<String, Long> {
        val body = try {
            transport.getText("https://api.github.com/repos/$repo/releases/tags/$tag", mapOf("Accept" to "application/vnd.github+json"))
        } catch (e: HttpStatusException) {
            when (e.code) {
                401, 403 -> throw IOException("GitHub rechazó el acceso (HTTP ${e.code}).")
                404 -> throw IOException("No se encuentra la release $tag de $repo.")
                else -> throw IOException("GitHub respondió HTTP ${e.code}")
            }
        }
        val json = Json.parseToJsonElement(body).jsonObject
        return json["assets"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["name"]!!.jsonPrimitive.content to o["id"]!!.jsonPrimitive.content.toLong()
        }
    }

    private companion object {
        val ASSET_HEADERS = mapOf("Accept" to "application/octet-stream", "X-GitHub-Api-Version" to "2022-11-28")
    }
}
