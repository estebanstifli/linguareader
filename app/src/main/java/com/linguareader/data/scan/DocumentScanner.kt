package com.linguareader.data.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.linguareader.R
import com.linguareader.data.db.DeviceDocumentDao
import com.linguareader.data.db.DeviceDocumentEntity
import com.linguareader.di.ApplicationScope
import com.linguareader.importer.ExtractorRegistry
import com.linguareader.importer.extract.QuickMetadata
import com.linguareader.ui.common.UiText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Estado de la búsqueda de documentos en el dispositivo. */
sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val folders: Int, val found: Int, val currentFolder: String) : ScanState
    data class Finished(val found: Int, val added: Int, val removed: Int) : ScanState
    data class Failed(val message: UiText) : ScanState
}

/** Acceso a los ficheros del dispositivo: «todos los archivos» en Android 11+, lectura del almacenamiento antes. */
object StorageAccess {
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** En Android 11+ el permiso se concede en una pantalla de Ajustes del sistema, no con un diálogo. */
    val needsSettingsScreen: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    const val RUNTIME_PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE
}

/**
 * Busca en el almacenamiento los documentos que la app sabe abrir y los guarda en `device_documents`, con título,
 * autor e idioma cuando el formato los trae (EPUB, FB2). Es incremental: un fichero que no cambió (tamaño y fecha)
 * no se vuelve a leer. Corre en segundo plano y no bloquea la interfaz.
 */
@Singleton
class DocumentScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ScanSettingsRepository,
    private val dao: DeviceDocumentDao,
    private val registry: ExtractorRegistry,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state
    private var job: Job? = null

    /** Formatos que se pueden buscar (los que la app sabe importar), en minúscula. */
    val supportedFormats: Set<String> get() = registry.supportedExtensions.map { it.lowercase() }.toSortedSet()

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = runCatching { scan() }.getOrElse {
                Log.w(TAG, "Búsqueda fallida", it)
                ScanState.Failed(UiText.res(R.string.scan_failed, it.message ?: it.javaClass.simpleName))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = ScanState.Idle
    }

    /** Al abrir la app: si el usuario lo quiere, hay acceso y la última búsqueda es de hace rato, busca sin molestar. */
    fun startIfDue() = scope.launch {
        val s = settings.settings.first()
        if (!s.autoStart || !s.offered) return@launch
        if (System.currentTimeMillis() - s.lastScanAt < AUTO_INTERVAL_MS) return@launch
        if (!StorageAccess.granted(context) && s.folders.isEmpty()) return@launch
        delay(AUTO_DELAY_MS) // que no compita con el arranque
        start()
    }

    private suspend fun scan(): ScanState {
        val s = settings.settings.first()
        val formats = (s.formats.ifEmpty { supportedFormats }).filter { it != "txt" || s.includeTxt }.toSet()
        val walker = DocumentWalker(formats, s.includeHidden, s.exclusions)
        val known = dao.stamps().associateBy { it.location }
        val seen = HashSet<String>()
        val batch = mutableListOf<DeviceDocumentEntity>()
        var folders = 0
        var added = 0
        val now = System.currentTimeMillis()
        _state.value = ScanState.Running(0, 0, "")

        suspend fun flush() {
            if (batch.isNotEmpty()) dao.upsert(batch.toList())
            batch.clear()
        }

        fun record(location: String, entity: () -> DeviceDocumentEntity, size: Long, modified: Long) {
            if (!seen.add(location)) return
            val stamp = known[location]
            if (stamp == null || stamp.sizeBytes != size || stamp.modifiedAt != modified) {
                batch += entity()
                if (stamp == null) added++
            }
        }

        val coroutine = coroutineContext
        val active = { !coroutine.isActive }
        if (StorageAccess.granted(context)) {
            for (root in fileRoots(s)) {
                walker.walk(root, cancelled = active, onFolder = { dir ->
                    folders++
                    if (folders % 25 == 0) _state.value = ScanState.Running(folders, seen.size, dir.path)
                }) { found ->
                    val f = found.file
                    record(f.absolutePath, { entityFor(f, found.format, now) }, f.length(), f.lastModified())
                }
                flush()
            }
        }
        for (tree in s.folders) {
            walkTree(Uri.parse(tree), formats, s.includeHidden, active) { uri, name, format, size, modified, folder ->
                record(uri.toString(), {
                    DeviceDocumentEntity(uri.toString(), name, format, size, modified, folder, QuickMetadata.titleFromName(name), null, null, now)
                }, size, modified)
            }
            flush()
        }
        if (active()) return ScanState.Idle

        val removed = known.keys - seen
        if (removed.isNotEmpty()) removed.chunked(500).forEach { dao.delete(it) }
        settings.update { it.copy(lastScanAt = System.currentTimeMillis()) }
        return ScanState.Finished(found = seen.size, added = added, removed = removed.size)
    }

    private fun entityFor(file: File, format: String, now: Long): DeviceDocumentEntity {
        val meta = QuickMetadata.read(file)
        return DeviceDocumentEntity(
            location = file.absolutePath,
            fileName = file.name,
            format = format,
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
            folder = file.parentFile?.absolutePath.orEmpty(),
            title = meta.title ?: QuickMetadata.titleFromName(file.name),
            author = meta.authors.firstOrNull(),
            language = meta.language,
            foundAt = now,
        )
    }

    /** Raíces a recorrer: todos los volúmenes (memoria interna y tarjetas SD) o solo Descargas. */
    private fun fileRoots(s: ScanSettings): List<File> {
        val roots = mutableListOf<File>()
        if (s.wholeDevice) roots += storageVolumes()
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (s.downloads && roots.none { downloads.absolutePath.startsWith(it.absolutePath) }) roots += downloads
        return roots.filter { it.isDirectory }
    }

    private fun storageVolumes(): List<File> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
        } else {
            // Antes de Android 11: la raíz de cada volumen es lo que hay antes de «/Android/data/<paquete>».
            @Suppress("DEPRECATION")
            listOf(Environment.getExternalStorageDirectory()) +
                context.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
                    dir.absolutePath.substringBefore("/Android/data/", "").takeIf { it.isNotEmpty() }?.let(::File)
                }
        }.distinctBy { it.absolutePath }

    /** Recorre una carpeta elegida con el selector del sistema (sin permiso de almacenamiento). */
    private fun walkTree(
        tree: Uri,
        formats: Set<String>,
        includeHidden: Boolean,
        cancelled: () -> Boolean,
        onFound: (uri: Uri, name: String, format: String, size: Long, modified: Long, folder: String) -> Unit,
    ) {
        val resolver = context.contentResolver
        val root = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return
        val pending = ArrayDeque<Pair<String, String>>().apply { add(root to root.substringAfter(':')) }
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (pending.isNotEmpty() && !cancelled()) {
            val (docId, label) = pending.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            runCatching {
                resolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (!includeHidden && name.startsWith(".")) continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(id to "$label/$name")
                        } else {
                            val format = DocumentWalker.formatOf(name)?.takeIf { it in formats } ?: continue
                            val size = c.getLong(3)
                            if (format == "txt" && size < DocumentWalker.MIN_TXT_BYTES) continue
                            onFound(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, format, size, c.getLong(4), label)
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "No se pudo leer la carpeta $label: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "DocumentScanner"
        const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val AUTO_DELAY_MS = 5_000L
    }
}
