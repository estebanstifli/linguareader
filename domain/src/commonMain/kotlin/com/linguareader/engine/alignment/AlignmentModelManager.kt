package com.linguareader.engine.alignment

import com.linguareader.core.platform.freeBytes
import com.linguareader.core.platform.ioDispatcher
import com.linguareader.engine.packs.GithubPackSource
import com.linguareader.engine.packs.PackError
import com.linguareader.engine.supertonic.DownloadResult
import com.linguareader.engine.supertonic.ModelFile
import com.linguareader.engine.supertonic.ModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** Modelo de alineación descargable para un idioma de lectura (entrada de aligners/catalog.json). */
@Serializable
data class AlignerModelSpec(
    val id: String,
    val language: String,
    val name: String,
    val source: String,
    val sourceRevision: String,
    val license: String,
    val licenseUrl: String,
    val files: List<AlignerFileSpec>,
    /** Adelanto de los inicios de palabra para este modelo (ver [AlignerModelFiles.startLeadMs]). */
    val startLeadMs: Long = 0,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** Licencias Creative Commons NC (p. ej. CC-BY-NC-4.0 de VoxPopuli): prohíben el uso comercial. */
    val nonCommercial: Boolean get() = license.split('-').any { it.equals("NC", ignoreCase = true) }
}

@Serializable
data class AlignerFileSpec(val path: String, val sizeBytes: Long, val sha256: String)

@Serializable
private data class AlignerCatalog(val version: Int, val models: List<AlignerModelSpec>)

/**
 * Descarga, verifica y elimina bajo demanda los modelos de alineación por palabra (uno por idioma
 * de lectura). Mismo esquema que el modelo de Supertonic: almacenamiento persistente de la app,
 * descarga reanudable con sha256 por fichero, errores publicados como [ModelState.Failed].
 *
 * Los ficheros son adjuntos de la release de paquetes de GitHub ([GithubPackSource]) con nombre
 * `<id>.<path>` (p. ej. `w2v2-en.model.onnx`).
 */
class AlignmentModelManager(
    private val fileSystem: FileSystem,
    private val filesDir: Path,
    private val appScope: CoroutineScope,
    private val source: GithubPackSource,
    catalogJson: () -> String?,
    private val log: (String) -> Unit = {},
) {
    val hostingConfigured: Boolean get() = source.configured

    val catalog: List<AlignerModelSpec> by lazy {
        runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<AlignerCatalog>(catalogJson() ?: error("sin catálogo")).models }
            .getOrElse { log("Catálogo de alineadores ilegible: ${it.message}"); emptyList() }
    }

    private val jobs = HashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    /** Se ejecuta antes de borrar un modelo (liberar la sesión ONNX). */
    var beforeDelete: () -> Unit = {}

    private val modelsRoot = filesDir / "models" / "alignment"

    init {
        refresh()
        appScope.launch(ioDispatcher) { removeRetired() }
    }

    /** Borra modelos y revisiones que ya no están en el catálogo (p. ej. el FR large de 355 MB, sustituido por VoxPopuli). */
    private fun removeRetired() {
        if (catalog.isEmpty()) return // catálogo ilegible: no borrar nada
        val keep = catalog.associate { it.id to it.sourceRevision.take(8) }
        fileSystem.listOrNull(modelsRoot).orEmpty().forEach { model ->
            val rev = keep[model.name]
            val stale = if (rev == null) listOf(model) else fileSystem.listOrNull(model).orEmpty().filter { it.name != rev }
            stale.forEach { runCatching { fileSystem.deleteRecursively(it) }.onSuccess { _ -> log("Modelo de alineación retirado: $it") } }
        }
    }

    fun specFor(language: String): AlignerModelSpec? = catalog.firstOrNull { it.language == language.substringBefore('-').lowercase() }

    private fun dirOf(spec: AlignerModelSpec) = modelsRoot / spec.id / spec.sourceRevision.take(8)

    private fun sizeOf(path: Path): Long = fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size ?: 0L

    private fun isInstalled(spec: AlignerModelSpec) = dirOf(spec).let { d -> spec.files.all { sizeOf(d / it.path) == it.sizeBytes } }

    private fun stateOf(spec: AlignerModelSpec): ModelState = runCatching {
        val dir = dirOf(spec)
        if (isInstalled(spec)) ModelState.Installed(spec.totalBytes, dir.toString())
        else ModelState.NotInstalled(spec.totalBytes, spec.files.sumOf { f -> sizeOf(dir / (f.path + ".part")) })
    }.getOrElse { ModelState.Failed(PackError.FolderInaccessible(it.message.orEmpty())) }

    fun refresh() {
        _states.update { old -> catalog.associate { spec -> spec.id to (old[spec.id]?.takeIf { jobs[spec.id]?.isActive == true } ?: stateOf(spec)) } }
    }

    /** Ficheros del modelo instalado para [language] (código `en`, `fr`…), o null. */
    fun filesFor(language: String): AlignerModelFiles? {
        val spec = specFor(language) ?: return null
        if (!isInstalled(spec)) return null
        val dir = dirOf(spec)
        val model = dir / "model.onnx"
        return AlignerModelFiles(model, dir / "vocab.json", version = "${spec.id}@${spec.sourceRevision.take(8)}:${sizeOf(model)}", startLeadMs = spec.startLeadMs)
    }

    private fun set(id: String, state: ModelState) = _states.update { it + (id to state) }

    fun download(id: String) {
        val spec = catalog.firstOrNull { it.id == id } ?: return
        if (jobs[id]?.isActive == true) return
        if (!hostingConfigured) {
            set(id, ModelState.Failed(PackError.RepoNotConfigured))
            return
        }
        jobs[id] = appScope.launch(ioDispatcher) {
            try {
                val dir = dirOf(spec).also { fileSystem.createDirectories(it) }
                val have = spec.files.sumOf { f -> sizeOf(dir / f.path) }
                val needed = spec.totalBytes - have
                val free = freeBytes(filesDir)
                if (free != null && free < needed + SAFETY_MARGIN) {
                    set(id, ModelState.Failed(PackError.NoSpace(needed / 1_000_000, free / 1_000_000)))
                    return@launch
                }
                set(id, ModelState.Downloading(0, spec.totalBytes, ""))
                val files = spec.files.map { ModelFile(it.path, it.sizeBytes, it.sha256) }
                val result = source.downloader(files) { f -> "${spec.id}.${f.path}" }.download(dir) { p ->
                    set(id, ModelState.Downloading(p.downloadedBytes, p.totalBytes, p.currentFile))
                }
                set(
                    id,
                    when (result) {
                        DownloadResult.Completed -> stateOf(spec)
                        is DownloadResult.Failed -> ModelState.Failed(PackError.DownloadInterruptedResumable(result.message))
                    },
                )
                log("Descarga de ${spec.id}: $result")
            } catch (e: CancellationException) {
                set(id, stateOf(spec))
                throw e
            } catch (e: Exception) {
                log("Fallo en la descarga de ${spec.id}: ${e.message ?: e::class.simpleName}")
                set(id, ModelState.Failed(PackError.DownloadError(e.message ?: e::class.simpleName.orEmpty())))
            }
        }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        catalog.firstOrNull { it.id == id }?.let { set(id, stateOf(it)) }
    }

    suspend fun delete(id: String) {
        val spec = catalog.firstOrNull { it.id == id } ?: return
        cancel(id)
        beforeDelete()
        withContext(ioDispatcher) { runCatching { fileSystem.deleteRecursively(dirOf(spec), mustExist = false) } }
        set(id, stateOf(spec))
    }

    private companion object {
        const val SAFETY_MARGIN = 50_000_000L
    }
}
