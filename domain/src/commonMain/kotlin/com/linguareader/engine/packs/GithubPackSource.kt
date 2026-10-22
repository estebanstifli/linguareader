package com.linguareader.engine.packs

import com.linguareader.engine.supertonic.HttpStatusException
import com.linguareader.engine.supertonic.HttpTransport
import com.linguareader.engine.supertonic.ModelDownloader
import com.linguareader.engine.supertonic.ModelFile
import com.linguareader.engine.supertonic.getText
import com.linguareader.engine.supertonic.platformHttpTransport
import okio.FileSystem
import okio.IOException

/**
 * Origen de los paquetes descargables (voces pregeneradas, modelos de alineación, bibliotecas EPUB): los
 * ficheros adjuntos de una release de GitHub ([repo], etiqueta [tag] o la que se indique).
 *
 * Se usan las URL directas de descarga (`github.com/<repo>/releases/download/<tag>/<fichero>`), que redirigen al
 * almacenamiento de GitHub y admiten `Range`. No pasan por la API, que sin token limita a 60 peticiones por hora
 * y por IP (los runners de CI y las redes compartidas la agotan). El repositorio es público: no hace falta token.
 */
class GithubPackSource(
    val repo: String,
    val tag: String,
    private val transport: HttpTransport = platformHttpTransport(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    val configured: Boolean get() = repo.isNotBlank() && tag.isNotBlank()

    /** Descargador para [files]; el fichero local `path` se busca como adjunto `assetName(file)` de la release. */
    fun downloader(files: List<ModelFile>, tag: String = this.tag, assetName: (ModelFile) -> String): ModelDownloader =
        ModelDownloader(
            baseUrl = "",
            files = files,
            transport = transport,
            urlFor = { f -> assetUrl(tag, assetName(f)) },
            fileSystem = fileSystem,
        )

    /**
     * Contenido de un adjunto pequeño de texto (un catálogo JSON).
     * @throws IOException si no se puede descargar (sin red, etiqueta o fichero inexistentes).
     */
    suspend fun text(assetName: String, tag: String = this.tag): String = try {
        transport.getText(assetUrl(tag, assetName))
    } catch (e: HttpStatusException) {
        throw IOException(
            when (e.code) {
                404 -> "No se encuentra $assetName en la release $tag de $repo."
                401, 403 -> "GitHub rechazó el acceso (HTTP ${e.code})."
                else -> "GitHub respondió HTTP ${e.code}"
            },
        )
    }

    private fun assetUrl(tag: String, assetName: String) = "https://github.com/$repo/releases/download/$tag/$assetName"
}
