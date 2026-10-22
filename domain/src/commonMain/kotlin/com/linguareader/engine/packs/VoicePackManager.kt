package com.linguareader.engine.packs

import com.linguareader.core.model.LanguageTags
import com.linguareader.core.platform.ioDispatcher
import com.linguareader.engine.supertonic.DownloadResult
import com.linguareader.engine.supertonic.ModelFile
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.engine.supertonic.SupertonicModelDir
import com.linguareader.engine.supertonic.SupertonicModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import okio.use

@Serializable
private data class VoicePackCatalog(val version: Int, val asset: String, val sizeBytes: Long, val sha256: String)

@Serializable
private data class VoicePackManifest(val version: Int, val items: List<VoicePackItem>)

@Serializable
private data class VoicePackItem(val path: String, val sizeBytes: Long, val sha256: String)

/** Tipos de audio pregenerado del paquete de voces. */
enum class VoiceClip(val fileName: String) { SAMPLE("sample.wav"), READY("ready.wav") }

/**
 * Paquete de voces de Supertonic ya sintetizadas (muestra de cada voz e idioma y la frase de
 * «preparando la lectura»). Se baja de la release de paquetes (zip de ~12 MB) automáticamente
 * cuando el modelo Supertonic queda instalado, y se descomprime en `<filesDir>/voice-pack/`.
 * Permite oír una voz o la frase de preparación sin cargar el modelo (decenas de segundos en móviles lentos).
 */
class VoicePackManager(
    private val fileSystem: FileSystem,
    private val filesDir: Path,
    private val cacheDir: Path,
    private val appScope: CoroutineScope,
    private val source: GithubPackSource,
    supertonic: SupertonicModelManager,
    catalogJson: () -> String?,
    private val log: (String) -> Unit = {},
) {
    private val catalog: VoicePackCatalog? by lazy {
        runCatching { JSON.decodeFromString<VoicePackCatalog>(catalogJson() ?: error("sin catálogo")) }
            .getOrElse { log("Catálogo del paquete de voces ilegible: ${it.message}"); null }
    }
    private val dir = filesDir / "voice-pack"
    private val marker get() = dir / ".installed"
    private val _state = MutableStateFlow<ModelState>(ModelState.NotInstalled(0, 0))
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        refresh()
        // «Al bajar el modelo deberíamos bajar unos wav ya creados».
        appScope.launch {
            supertonic.state.collect { s -> if (s is ModelState.Installed && _state.value is ModelState.NotInstalled) download() }
        }
    }

    private fun installedVersion() = runCatching { marker.takeIf { fileSystem.exists(it) }?.let { fileSystem.read(it) { readUtf8() } }?.trim() }.getOrNull()

    fun refresh() {
        if (job?.isActive == true) return
        val c = catalog
        _state.value = when {
            c == null -> ModelState.Failed(PackError.VoicePackCatalogUnavailable)
            installedVersion() == "${c.version}:${c.sha256}" -> ModelState.Installed(c.sizeBytes, dir.toString())
            else -> ModelState.NotInstalled(c.sizeBytes, 0)
        }
    }

    /** WAV pregenerado de la voz en ese idioma, o null si el paquete no está instalado. */
    fun clip(languageTag: String, voice: String, kind: VoiceClip): Path? {
        if (_state.value !is ModelState.Installed) return null
        return (dir / LanguageTags.primary(languageTag) / voice / kind.fileName).takeIf { fileSystem.exists(it) }
    }

    fun download() {
        val c = catalog ?: return
        if (job?.isActive == true) return
        job = appScope.launch(ioDispatcher) {
            try {
                if (!source.configured) {
                    _state.value = ModelState.Failed(PackError.RepoNotConfigured)
                    return@launch
                }
                _state.value = ModelState.Downloading(0, c.sizeBytes, c.asset)
                val tmp = (cacheDir / "voice-pack-dl").also { fileSystem.createDirectories(it) }
                val file = ModelFile(c.asset, c.sizeBytes, c.sha256)
                val result = source.downloader(listOf(file)) { it.path }.download(tmp) { p ->
                    _state.value = ModelState.Downloading(p.downloadedBytes, p.totalBytes, c.asset)
                }
                if (result is DownloadResult.Failed) {
                    _state.value = ModelState.Failed(PackError.DownloadInterrupted(result.message))
                    return@launch
                }
                _state.value = ModelState.Verifying(0f)
                install(tmp / c.asset, c)
                fileSystem.deleteRecursively(tmp, mustExist = false)
                _state.value = ModelState.Installed(c.sizeBytes, dir.toString())
            } catch (e: CancellationException) {
                refresh()
                throw e
            } catch (e: Exception) {
                log("Paquete de voces: ${e.message ?: e::class.simpleName}")
                _state.value = ModelState.Failed(PackError.DownloadError(e.message ?: e::class.simpleName.orEmpty()))
            }
        }
    }

    suspend fun delete() {
        job?.cancel()
        withContext(ioDispatcher) { runCatching { fileSystem.deleteRecursively(dir, mustExist = false) } }
        refresh()
    }

    /** Descomprime y verifica cada WAV con el sha256 del manifiesto antes de dar el paquete por instalado. */
    private fun install(zip: Path, c: VoicePackCatalog) {
        val staging = (filesDir / "voice-pack.tmp").also { fileSystem.deleteRecursively(it, mustExist = false); fileSystem.createDirectories(it) }
        val archive = fileSystem.openZip(zip)
        val root = "/".toPath()
        for (entry in archive.listRecursively(root)) {
            val meta = archive.metadataOrNull(entry) ?: continue
            if (meta.isDirectory) continue
            val relative = entry.relativeTo(root)
            if (relative.isAbsolute || relative.segments.any { it == ".." }) throw IOException("Entrada de zip no válida: $entry")
            val target = staging / relative
            target.parent?.let { fileSystem.createDirectories(it) }
            archive.source(entry).use { source -> fileSystem.write(target) { writeAll(source) } }
        }
        val manifest = JSON.decodeFromString<VoicePackManifest>(fileSystem.read(staging / "manifest.json") { readUtf8() })
        manifest.items.forEachIndexed { i, item ->
            val f = staging / item.path
            val size = fileSystem.metadataOrNull(f)?.size
            check(size == item.sizeBytes && SupertonicModelDir.sha256(f, fileSystem) == item.sha256) { "Fichero corrupto en el paquete de voces: ${item.path}" }
            _state.value = ModelState.Verifying((i + 1f) / manifest.items.size)
        }
        fileSystem.deleteRecursively(dir, mustExist = false)
        fileSystem.atomicMove(staging, dir)
        fileSystem.write(marker) { writeUtf8("${c.version}:${c.sha256}") }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
