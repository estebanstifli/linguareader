package com.linguareader.engine.supertonic

import okio.Path.Companion.toOkioPath
import android.content.Context
import android.os.Debug
import android.os.StatFs
import android.util.Log
import com.linguareader.R
import com.linguareader.di.ApplicationScope
import com.linguareader.ui.common.UiText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ModelState {
    data class NotInstalled(val totalBytes: Long, val partialBytes: Long) : ModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long, val currentFile: String) : ModelState
    data class Verifying(val fraction: Float) : ModelState
    data class Installed(val bytesOnDisk: Long, val path: String) : ModelState
    data class Failed(val message: UiText) : ModelState
}

/**
 * Descarga, verifica y elimina el modelo Supertonic 3 bajo demanda.
 *
 * Ubicación: almacenamiento **interno** de la app (`files/models/…`). Siempre pertenece a la app
 * (no le afectan los permisos de `Android/data`, que fallan si `adb push` creó las carpetas como
 * usuario `shell`) y se borra al desinstalar. En desarrollo se copia con `adb` + `run-as` (run.ps1).
 * La descarga reanuda ficheros parciales y verifica el sha256 de cada fichero.
 * Ninguna operación lanza excepciones a la app: los errores se publican como [ModelState.Failed].
 */
@Singleton
class SupertonicModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    val modelDir: File by lazy {
        File(context.filesDir, "models/${SupertonicModel.ENGINE_ID}/${SupertonicModel.REVISION}")
    }

    private val _state = MutableStateFlow<ModelState>(currentState())
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var job: Job? = null

    /** Se ejecuta antes de borrar el modelo (liberar sesiones ONNX). */
    var beforeDelete: () -> Unit = {}

    private fun currentState(): ModelState = try {
        val dir = SupertonicModelDir(modelDir.toOkioPath())
        if (dir.isComplete()) ModelState.Installed(dir.bytesOnDisk(), modelDir.path)
        else ModelState.NotInstalled(SupertonicModel.TOTAL_BYTES, if (modelDir.exists()) dir.bytesOnDisk() else 0)
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo leer la carpeta del modelo", e)
        ModelState.Failed(UiText.res(R.string.model_folder_inaccessible, e.message.orEmpty()))
    }

    fun refresh() {
        if (job?.isActive != true) _state.value = currentState()
    }

    fun download() {
        if (job?.isActive == true) return
        job = appScope.launch(Dispatchers.IO) {
            try {
                val needed = SupertonicModel.TOTAL_BYTES - (if (modelDir.exists()) SupertonicModelDir(modelDir.toOkioPath()).bytesOnDisk() else 0)
                if (!modelDir.mkdirs() && !modelDir.isDirectory) {
                    _state.value = ModelState.Failed(UiText.res(R.string.model_folder_create_failed))
                    return@launch
                }
                // Espacio libre del volumen de datos de la app (el directorio base siempre es accesible).
                val free = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrElse { context.filesDir.usableSpace }
                if (free < needed + SAFETY_MARGIN) {
                    _state.value = ModelState.Failed(UiText.res(R.string.model_no_space, needed.mb(), free.mb()))
                    return@launch
                }
                _state.value = ModelState.Downloading(0, SupertonicModel.TOTAL_BYTES, "")
                val result = ModelDownloader(SupertonicModel.BASE_URL, SupertonicModel.FILES).download(modelDir) { p ->
                    _state.value = ModelState.Downloading(p.downloadedBytes, p.totalBytes, p.currentFile)
                }
                _state.value = when (result) {
                    DownloadResult.Completed -> currentState()
                    is DownloadResult.Failed -> ModelState.Failed(UiText.res(R.string.model_download_interrupted_resumable, result.message))
                }
                Log.i(TAG, "Descarga de Supertonic: $result")
            } catch (e: CancellationException) {
                _state.value = currentState()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fallo en la descarga de Supertonic", e)
                _state.value = ModelState.Failed(UiText.res(R.string.model_download_error, e.message ?: e.javaClass.simpleName))
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
        job = appScope.launch(Dispatchers.IO) {
            try {
                val dir = SupertonicModelDir(modelDir.toOkioPath())
                dir.fileSystem.delete(dir.file(SupertonicModel.MARKER), mustExist = false)
                val bad = dir.verify { done -> _state.value = ModelState.Verifying(done.toFloat() / SupertonicModel.TOTAL_BYTES) }
                _state.value = if (bad.isEmpty()) currentState() else ModelState.Failed(UiText.res(R.string.model_files_corrupt, bad.joinToString()))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = ModelState.Failed(UiText.res(R.string.model_verify_error, e.message.orEmpty()))
            }
        }
    }

    suspend fun delete() {
        cancel()
        beforeDelete()
        withContext(Dispatchers.IO) { runCatching { SupertonicModelDir(modelDir.toOkioPath()).delete() } }
        _state.value = currentState()
    }

    private fun Long.mb() = this / 1_000_000

    companion object {
        private const val TAG = "SupertonicModel"
        private const val SAFETY_MARGIN = 50_000_000L
    }
}

/** Memoria del proceso en Android: PSS total (Java + nativa, incluye ONNX Runtime). */
val AndroidMemoryProbe = MemoryProbe { Debug.getPss() * 1024L }
