package com.linguareader.engine.alignment

import okio.Path.Companion.toOkioPath
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.linguareader.R
import com.linguareader.engine.packs.GithubPackSource
import com.linguareader.engine.supertonic.DownloadResult
import com.linguareader.engine.supertonic.ModelFile
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.ui.common.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Modelo de alineación descargable para un idioma de lectura (entrada de assets/aligners/catalog.json). */
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
 * de lectura). Mismo esquema que el modelo de Supertonic: almacenamiento interno de la app,
 * descarga reanudable con sha256 por fichero, errores publicados como [ModelState.Failed].
 *
 * Los ficheros son adjuntos de la release de paquetes de GitHub ([GithubPackSource]) con nombre
 * `<id>.<path>` (p. ej. `w2v2-en.model.onnx`).
 */
class AlignmentModelManager(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val source: GithubPackSource,
) {
    val hostingConfigured: Boolean get() = source.configured

    val catalog: List<AlignerModelSpec> by lazy {
        runCatching {
            context.assets.open("aligners/catalog.json").use { Json { ignoreUnknownKeys = true }.decodeFromString<AlignerCatalog>(it.reader().readText()) }.models
        }.getOrElse { Log.e(TAG, "Catálogo de alineadores ilegible", it); emptyList() }
    }

    private val jobs = HashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    /** Se ejecuta antes de borrar un modelo (liberar la sesión ONNX). */
    var beforeDelete: () -> Unit = {}

    init {
        refresh()
        appScope.launch(Dispatchers.IO) { removeRetired() }
    }

    /** Borra modelos y revisiones que ya no están en el catálogo (p. ej. el FR large de 355 MB, sustituido por VoxPopuli). */
    private fun removeRetired() {
        if (catalog.isEmpty()) return // catálogo ilegible: no borrar nada
        val keep = catalog.associate { it.id to it.sourceRevision.take(8) }
        File(context.filesDir, "models/alignment").listFiles()?.forEach { model ->
            val rev = keep[model.name]
            val stale = if (rev == null) listOf(model) else model.listFiles()?.filter { it.name != rev }.orEmpty()
            stale.forEach { runCatching { it.deleteRecursively() }.onSuccess { _ -> Log.i(TAG, "Modelo de alineación retirado: ${it.path}") } }
        }
    }

    fun specFor(language: String): AlignerModelSpec? = catalog.firstOrNull { it.language == language.substringBefore('-').lowercase() }

    private fun dirOf(spec: AlignerModelSpec) = File(context.filesDir, "models/alignment/${spec.id}/${spec.sourceRevision.take(8)}")

    private fun isInstalled(spec: AlignerModelSpec) = dirOf(spec).let { d -> spec.files.all { File(d, it.path).length() == it.sizeBytes } }

    private fun stateOf(spec: AlignerModelSpec): ModelState = runCatching {
        val dir = dirOf(spec)
        if (isInstalled(spec)) ModelState.Installed(spec.totalBytes, dir.path)
        else ModelState.NotInstalled(spec.totalBytes, spec.files.sumOf { f -> File(dir, f.path + ".part").takeIf { it.exists() }?.length() ?: 0L })
    }.getOrElse { ModelState.Failed(UiText.res(R.string.model_folder_inaccessible, it.message.orEmpty())) }

    fun refresh() {
        _states.update { old -> catalog.associate { spec -> spec.id to (old[spec.id]?.takeIf { jobs[spec.id]?.isActive == true } ?: stateOf(spec)) } }
    }

    /** Ficheros del modelo instalado para [language] (código `en`, `fr`…), o null. */
    fun filesFor(language: String): AlignerModelFiles? {
        val spec = specFor(language) ?: return null
        if (!isInstalled(spec)) return null
        val dir = dirOf(spec)
        val model = File(dir, "model.onnx")
        return AlignerModelFiles(model.toOkioPath(), File(dir, "vocab.json").toOkioPath(), version = "${spec.id}@${spec.sourceRevision.take(8)}:${model.length()}", startLeadMs = spec.startLeadMs)
    }

    private fun set(id: String, state: ModelState) = _states.update { it + (id to state) }

    fun download(id: String) {
        val spec = catalog.firstOrNull { it.id == id } ?: return
        if (jobs[id]?.isActive == true) return
        if (!hostingConfigured) {
            set(id, ModelState.Failed(UiText.res(R.string.model_repo_not_configured)))
            return
        }
        jobs[id] = appScope.launch(Dispatchers.IO) {
            try {
                val dir = dirOf(spec).also { it.mkdirs() }
                val have = spec.files.sumOf { f -> File(dir, f.path).takeIf { it.exists() }?.length() ?: 0L }
                val needed = spec.totalBytes - have
                val free = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrElse { context.filesDir.usableSpace }
                if (free < needed + SAFETY_MARGIN) {
                    set(id, ModelState.Failed(UiText.res(R.string.model_no_space, needed / 1_000_000, free / 1_000_000)))
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
                        is DownloadResult.Failed -> ModelState.Failed(UiText.res(R.string.model_download_interrupted_resumable, result.message))
                    },
                )
                Log.i(TAG, "Descarga de ${spec.id}: $result")
            } catch (e: CancellationException) {
                set(id, stateOf(spec))
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fallo en la descarga de ${spec.id}", e)
                set(id, ModelState.Failed(UiText.res(R.string.model_download_error, e.message ?: e.javaClass.simpleName)))
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
        withContext(Dispatchers.IO) { runCatching { dirOf(spec).deleteRecursively() } }
        set(id, stateOf(spec))
    }

    private companion object {
        const val TAG = "AlignerModels"
        const val SAFETY_MARGIN = 50_000_000L
    }
}
