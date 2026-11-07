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
 * Origen de los paquetes descargables (voces pregeneradas por idioma, modelos de alineación, bibliotecas EPUB,
 * narraciones): ficheros estáticos en `<baseUrl>/<tag>/<fichero>`.
 *
 * Hoy es un bucket R2 público de Cloudflare con dominio propio (`https://files.lingoaudiobook.eu`), que sirve con CDN y
 * admite `Range` (reanudación). Las etiquetas (`packs-v1`, `library-v1`) son prefijos de ruta, heredados de cuando
 * los paquetes eran adjuntos de releases de GitHub: cambiar de alojamiento es cambiar [baseUrl]
 * (`-PpacksBaseUrl=` al compilar). No hace falta token ni cuenta.
 */
class PackSource(
    val baseUrl: String,
    val tag: String,
    private val transport: HttpTransport = platformHttpTransport(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    val configured: Boolean get() = baseUrl.startsWith("https://") && tag.isNotBlank()

    /** Host que se muestra al usuario («files.lingoaudiobook.eu»). */
    val host: String get() = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')

    /** Descargador para [files]; el fichero local `path` se busca como `assetName(file)` bajo [tag]. */
    fun downloader(files: List<ModelFile>, tag: String = this.tag, assetName: (ModelFile) -> String): ModelDownloader =
        ModelDownloader(
            baseUrl = "",
            files = files,
            transport = transport,
            urlFor = { f -> assetUrl(tag, assetName(f)) },
            fileSystem = fileSystem,
        )

    /**
     * Contenido de un fichero pequeño de texto (un catálogo JSON).
     * @throws IOException si no se puede descargar (sin red, prefijo o fichero inexistentes).
     */
    suspend fun text(assetName: String, tag: String = this.tag): String = try {
        transport.getText(assetUrl(tag, assetName))
    } catch (e: HttpStatusException) {
        throw IOException(
            when (e.code) {
                404 -> "No se encuentra $assetName en $host/$tag."
                401, 403 -> "$host rechazó el acceso (HTTP ${e.code})."
                else -> "$host respondió HTTP ${e.code}"
            },
        )
    }

    fun assetUrl(tag: String, assetName: String) = "${baseUrl.trimEnd('/')}/$tag/$assetName"

    companion object {
        const val DEFAULT_BASE_URL = "https://files.lingoaudiobook.eu"
    }
}
