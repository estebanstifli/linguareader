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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
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

/** Paquete de voces de un idioma en el catálogo: zip `voices-<idioma>-v<N>.zip` con su tamaño y sha256. */
@Serializable
data class VoicePackInfo(val asset: String, val sizeBytes: Long, val sha256: String)

@Serializable
private data class VoicePackCatalog(val version: Int, val languages: Map<String, VoicePackInfo> = emptyMap())

@Serializable
private data class VoicePackManifest(val version: Int, val items: List<VoicePackItem>)

@Serializable
private data class VoicePackItem(val path: String, val sizeBytes: Long, val sha256: String)

/** Tipos de audio pregenerado del paquete de voces. */
enum class VoiceClip(val fileName: String) { SAMPLE("sample.wav"), READY("ready.wav") }

/**
 * Paquetes de voces de Supertonic ya sintetizadas, **uno por idioma** (muestra de cada voz y frase de «preparando la
 * lectura»; 4–7 MB cada uno). Permiten oír una voz o la frase de preparación sin cargar el modelo (decenas de segundos
 * en móviles lentos).
 *
 * - El catálogo (`voices.json`: idioma → zip) viene con la app ([catalogJson]) y se refresca desde el origen de
 *   paquetes ([PackSource]), para que los idiomas nuevos lleguen sin actualizar la app.
 * - Se descarga **solo el idioma que hace falta**: el que se aprende ([learningLanguage], al instalarse el modelo o al
 *   cambiar de idioma) y el que el usuario elige en Ajustes › Voz ([ensure]). Nada se baja si el modelo Supertonic no
 *   está instalado.
 * - En disco: `<filesDir>/voice-pack/<idioma>/<voz>/{sample,ready}.wav` con un marcador `.installed` (sha256 del zip)
 *   por idioma. El antiguo paquete único (`voice-pack/.installed`) se borra en la primera ejecución.
 */
class VoicePackManager(
    private val fileSystem: FileSystem,
    private val filesDir: Path,
    private val cacheDir: Path,
    private val appScope: CoroutineScope,
    private val source: PackSource,
    private val supertonic: SupertonicModelManager,
    catalogJson: () -> String?,
    /** Idioma que se aprende (preferencia): su paquete se descarga solo. */
    learningLanguage: Flow<String?> = flowOf(null),
    private val log: (String) -> Unit = {},
) {
    private val dir = filesDir / "voice-pack"
    private val _catalog = MutableStateFlow(bundledCatalog(catalogJson()))
    /** Idiomas con paquete publicado. */
    val catalog: StateFlow<Map<String, VoicePackInfo>> = _catalog.asStateFlow()
    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    /** Estado por idioma (solo idiomas del catálogo o ya en disco). */
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()
    private val jobs = HashMap<String, Job>()

    init {
        migrateLegacyPack()
        refresh()
        appScope.launch(ioDispatcher) { refreshCatalog() }
        // El idioma que se aprende tiene siempre sus voces: al instalar el modelo y al cambiar de idioma.
        appScope.launch {
            combine(supertonic.state, learningLanguage.distinctUntilChanged()) { s, lang -> (s is ModelState.Installed) to lang }
                .distinctUntilChanged()
                .collect { (installed, lang) -> if (installed && lang != null) ensure(lang) }
        }
    }

    private fun bundledCatalog(json: String?): Map<String, VoicePackInfo> = runCatching {
        JSON.decodeFromString<VoicePackCatalog>(json ?: error("sin catálogo")).languages
    }.getOrElse { log("Catálogo del paquete de voces ilegible: ${it.message}"); emptyMap() }

    /** Catálogo remoto (idiomas nuevos o paquetes regenerados); sin red se queda el incluido. */
    suspend fun refreshCatalog() {
        if (!source.configured) return
        runCatching { JSON.decodeFromString<VoicePackCatalog>(source.text(CATALOG_ASSET)).languages }
            .onSuccess { remote -> _catalog.value = _catalog.value + remote; refresh() }
            .onFailure { log("Catálogo remoto de voces no disponible: ${it.message}") }
    }

    /** Un paquete único de versiones anteriores (`voice-pack/.installed` en la raíz) ya no sirve: se quita. */
    private fun migrateLegacyPack() {
        if (fileSystem.exists(dir / ".installed")) {
            runCatching { fileSystem.deleteRecursively(dir) }
            log("Paquete de voces antiguo eliminado; se descargará el del idioma que se aprende")
        }
    }

    private fun langDir(lang: String) = dir / lang
    private fun marker(lang: String) = langDir(lang) / ".installed"
    private fun installedSha(lang: String) =
        runCatching { marker(lang).takeIf { fileSystem.exists(it) }?.let { fileSystem.read(it) { readUtf8() } }?.trim() }.getOrNull()

    fun refresh() {
        val onDisk = fileSystem.listOrNull(dir).orEmpty().filter { fileSystem.metadataOrNull(it)?.isDirectory == true }.map { it.name }
        val langs = _catalog.value.keys + onDisk
        _states.update { current ->
            langs.associateWith { lang ->
                val running = current[lang]
                if (jobs[lang]?.isActive == true && running != null) return@associateWith running
                val info = _catalog.value[lang]
                val sha = installedSha(lang)
                when {
                    sha != null && (info == null || info.sha256 == sha) -> ModelState.Installed(info?.sizeBytes ?: dirSize(langDir(lang)), langDir(lang).toString())
                    running is ModelState.Failed -> running
                    else -> ModelState.NotInstalled(info?.sizeBytes ?: 0, 0)
                }
            }
        }
    }

    private fun dirSize(path: Path): Long = fileSystem.listRecursively(path).sumOf { fileSystem.metadataOrNull(it)?.size ?: 0L }

    /** Hay paquete publicado para el idioma. */
    fun available(languageTag: String): Boolean = LanguageTags.primary(languageTag) in _catalog.value

    fun isInstalled(languageTag: String): Boolean = _states.value[LanguageTags.primary(languageTag)] is ModelState.Installed

    /** WAV pregenerado de la voz en ese idioma, o null si el paquete del idioma no está instalado. */
    fun clip(languageTag: String, voice: String, kind: VoiceClip): Path? {
        val lang = LanguageTags.primary(languageTag)
        if (_states.value[lang] !is ModelState.Installed) return null
        return (langDir(lang) / voice / kind.fileName).takeIf { fileSystem.exists(it) }
    }

    /**
     * Garantiza el paquete del idioma: si falta y el modelo Supertonic está instalado, lo descarga en segundo plano.
     * Devuelve true si ya estaba o se ha empezado a bajar; false si no hay paquete para el idioma o no toca (sin modelo).
     */
    fun ensure(languageTag: String): Boolean {
        val lang = LanguageTags.primary(languageTag)
        if (lang !in _catalog.value) return false
        when (_states.value[lang]) {
            is ModelState.Installed, is ModelState.Downloading, is ModelState.Verifying -> return true
            else -> Unit
        }
        if (supertonic.state.value !is ModelState.Installed) return false
        download(lang)
        return true
    }

    fun download(languageTag: String) {
        val lang = LanguageTags.primary(languageTag)
        val info = _catalog.value[lang] ?: return
        if (jobs[lang]?.isActive == true) return
        jobs[lang] = appScope.launch(ioDispatcher) {
            val tmp = cacheDir / "voice-pack-dl" / lang
            try {
                if (!source.configured) {
                    set(lang, ModelState.Failed(PackError.RepoNotConfigured))
                    return@launch
                }
                set(lang, ModelState.Downloading(0, info.sizeBytes, info.asset))
                fileSystem.createDirectories(tmp)
                val file = ModelFile(info.asset, info.sizeBytes, info.sha256)
                val result = source.downloader(listOf(file)) { it.path }.download(tmp) { p ->
                    set(lang, ModelState.Downloading(p.downloadedBytes, p.totalBytes, info.asset))
                }
                if (result is DownloadResult.Failed) {
                    set(lang, ModelState.Failed(PackError.DownloadInterrupted(result.message)))
                    return@launch
                }
                set(lang, ModelState.Verifying(0f))
                install(lang, tmp / info.asset, info)
                fileSystem.deleteRecursively(tmp, mustExist = false)
                set(lang, ModelState.Installed(info.sizeBytes, langDir(lang).toString()))
                log("Voces de $lang instaladas (${info.sizeBytes / 1_000_000} MB)")
            } catch (e: CancellationException) {
                refresh()
                throw e
            } catch (e: Exception) {
                log("Paquete de voces $lang: ${e.message ?: e::class.simpleName}")
                set(lang, ModelState.Failed(PackError.DownloadError(e.message ?: e::class.simpleName.orEmpty())))
            }
        }
    }

    suspend fun delete(languageTag: String) {
        val lang = LanguageTags.primary(languageTag)
        jobs[lang]?.cancel()
        withContext(ioDispatcher) { runCatching { fileSystem.deleteRecursively(langDir(lang), mustExist = false) } }
        refresh()
    }

    suspend fun deleteAll() {
        jobs.values.forEach { it.cancel() }
        withContext(ioDispatcher) { runCatching { fileSystem.deleteRecursively(dir, mustExist = false) } }
        refresh()
    }

    /** Bytes en disco de todos los paquetes instalados. */
    fun installedBytes(): Long = _states.value.values.filterIsInstance<ModelState.Installed>().sumOf { it.bytesOnDisk }

    private fun set(lang: String, state: ModelState) = _states.update { it + (lang to state) }

    /** Descomprime y verifica cada WAV con el sha256 del manifiesto antes de dar el paquete por instalado. */
    private fun install(lang: String, zip: Path, info: VoicePackInfo) {
        val staging = (filesDir / "voice-pack.tmp" / lang).also { fileSystem.deleteRecursively(it, mustExist = false); fileSystem.createDirectories(it) }
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
            check(size == item.sizeBytes && SupertonicModelDir.sha256(f, fileSystem) == item.sha256) { "Fichero corrupto en el paquete de voces: $lang/${item.path}" }
            set(lang, ModelState.Verifying((i + 1f) / manifest.items.size))
        }
        fileSystem.createDirectories(dir)
        fileSystem.deleteRecursively(langDir(lang), mustExist = false)
        fileSystem.atomicMove(staging, langDir(lang))
        fileSystem.write(marker(lang)) { writeUtf8(info.sha256) }
    }

    companion object {
        const val CATALOG_ASSET = "voices.json"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
