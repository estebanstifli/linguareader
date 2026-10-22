package com.linguareader.engine.supertonic

import com.linguareader.core.platform.freeBytes
import com.linguareader.core.platform.ioDispatcher
import com.linguareader.engine.packs.PackError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Descarga, verifica y elimina el modelo Supertonic 3 bajo demanda.
 *
 * Ubicación: almacenamiento persistente de la app (`<filesDir>/models/…`): pertenece a la app y se borra al
 * desinstalar. En desarrollo Android se copia con `adb` + `run-as` (run.ps1).
 * La descarga reanuda ficheros parciales y verifica el sha256 de cada fichero.
 * Ninguna operación lanza excepciones a la app: los errores se publican como [ModelState.Failed].
 */
class SupertonicModelManager(
    private val fileSystem: FileSystem,
    private val filesDir: Path,
    private val appScope: CoroutineScope,
    private val transport: HttpTransport = platformHttpTransport(),
    private val log: (String) -> Unit = {},
) {
    val modelDir: Path = filesDir / "models" / SupertonicModel.ENGINE_ID / SupertonicModel.REVISION

    private val _state = MutableStateFlow<ModelState>(currentState())
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var job: Job? = null

    /** Se ejecuta antes de borrar el modelo (liberar sesiones ONNX). */
    var beforeDelete: () -> Unit = {}

    private fun currentState(): ModelState = try {
        val dir = SupertonicModelDir(modelDir, fileSystem)
        if (dir.isComplete()) ModelState.Installed(dir.bytesOnDisk(), modelDir.toString())
        else ModelState.NotInstalled(SupertonicModel.TOTAL_BYTES, if (fileSystem.exists(modelDir)) dir.bytesOnDisk() else 0)
    } catch (e: Exception) {
        log("No se pudo leer la carpeta del modelo: ${e.message}")
        ModelState.Failed(PackError.FolderInaccessible(e.message.orEmpty()))
    }

    fun refresh() {
        if (job?.isActive != true) _state.value = currentState()
    }

    fun download() {
        if (job?.isActive == true) return
        job = appScope.launch(ioDispatcher) {
            try {
                val needed = SupertonicModel.TOTAL_BYTES - (if (fileSystem.exists(modelDir)) SupertonicModelDir(modelDir, fileSystem).bytesOnDisk() else 0)
                if (runCatching { fileSystem.createDirectories(modelDir) }.isFailure || fileSystem.metadataOrNull(modelDir)?.isDirectory != true) {
                    _state.value = ModelState.Failed(PackError.FolderCreateFailed)
                    return@launch
                }
                // Espacio libre del volumen de datos de la app.
                val free = freeBytes(filesDir)
                if (free != null && free < needed + SAFETY_MARGIN) {
                    _state.value = ModelState.Failed(PackError.NoSpace(needed.mb(), free.mb()))
                    return@launch
                }
                _state.value = ModelState.Downloading(0, SupertonicModel.TOTAL_BYTES, "")
                val result = ModelDownloader(SupertonicModel.BASE_URL, SupertonicModel.FILES, transport, fileSystem = fileSystem).download(modelDir) { p ->
                    _state.value = ModelState.Downloading(p.downloadedBytes, p.totalBytes, p.currentFile)
                }
                _state.value = when (result) {
                    DownloadResult.Completed -> currentState()
                    is DownloadResult.Failed -> ModelState.Failed(PackError.DownloadInterruptedResumable(result.message))
                }
                log("Descarga de Supertonic: $result")
            } catch (e: CancellationException) {
                _state.value = currentState()
                throw e
            } catch (e: Exception) {
                log("Fallo en la descarga de Supertonic: ${e.message ?: e::class.simpleName}")
                _state.value = ModelState.Failed(PackError.DownloadError(e.message ?: e::class.simpleName.orEmpty()))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = currentState()
    }

    /** Verificación completa (sha256) — útil tras copiar el modelo con adb. */
    fun verify() {
        if (job?.isActive == true) return
        job = appScope.launch(ioDispatcher) {
            try {
                val dir = SupertonicModelDir(modelDir, fileSystem)
                fileSystem.delete(dir.file(SupertonicModel.MARKER), mustExist = false)
                val bad = dir.verify { done -> _state.value = ModelState.Verifying(done.toFloat() / SupertonicModel.TOTAL_BYTES) }
                _state.value = if (bad.isEmpty()) currentState() else ModelState.Failed(PackError.FilesCorrupt(bad.joinToString()))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = ModelState.Failed(PackError.VerifyError(e.message.orEmpty()))
            }
        }
    }

    suspend fun delete() {
        cancel()
        beforeDelete()
        withContext(ioDispatcher) { runCatching { SupertonicModelDir(modelDir, fileSystem).delete() } }
        _state.value = currentState()
    }

    private fun Long.mb() = this / 1_000_000

    companion object {
        private const val SAFETY_MARGIN = 50_000_000L
    }
}
