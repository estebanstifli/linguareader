package com.linguareader.engine.packs

import android.content.Context
import android.util.Log
import com.linguareader.BuildConfig
import com.linguareader.R
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.LanguageTags
import com.linguareader.data.content.BookImportService
import com.linguareader.di.ApplicationScope
import com.linguareader.engine.supertonic.DownloadResult
import com.linguareader.engine.supertonic.ModelFile
import com.linguareader.ui.common.UiText
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Estado de la biblioteca de un idioma. */
sealed interface LibraryState {
    data object NotInstalled : LibraryState
    data class Downloading(val bytes: Long, val total: Long) : LibraryState
    data class Installing(val done: Int, val total: Int) : LibraryState
    data class Installed(val version: Int, val books: Int, val updateAvailable: Boolean) : LibraryState
    data class Failed(val message: UiText) : LibraryState
}

/**
 * Bibliotecas de libros graduados por idioma: un zip de EPUB por idioma en la release
 * `BuildConfig.LIBRARY_TAG` (tools/build_library.py). Al instalar, cada EPUB se importa como libro
 * del usuario con un id estable (`u-lib-<idioma>-<slug>`), así que actualizar la biblioteca conserva
 * el progreso, los marcadores y el vocabulario; los libros retirados en la versión nueva se borran.
 *
 * El catálogo (`libraries.json`) viene en el APK y se refresca desde la release, para que las
 * bibliotecas nuevas o ampliadas lleguen sin actualizar la app.
 */
@Singleton
class LibraryPackManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val source: GithubPackSource,
    private val importer: BookImportService,
) {
    private val tag = BuildConfig.LIBRARY_TAG
    private val dir = File(context.filesDir, "libraries")
    private val _catalog = MutableStateFlow(bundledCatalog())
    val catalog: StateFlow<Map<String, LibraryInfo>> = _catalog.asStateFlow()
    private val _states = MutableStateFlow<Map<String, LibraryState>>(emptyMap())
    val states: StateFlow<Map<String, LibraryState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    init {
        refreshStates()
        appScope.launch(Dispatchers.IO) { refreshCatalog() }
    }

    private fun bundledCatalog(): Map<String, LibraryInfo> = runCatching {
        context.assets.open("packs/libraries.json").use {
            LibraryArchive.json.decodeFromString<LibraryCatalog>(it.reader().readText()).libraries
        }
    }.getOrElse { Log.w(TAG, "Sin catálogo de bibliotecas en assets", it); emptyMap() }

    /** Catálogo de la release (versiones más nuevas que las del APK); sin red se queda el del APK. */
    suspend fun refreshCatalog() {
        if (!source.configured) return
        runCatching { LibraryArchive.json.decodeFromString<LibraryCatalog>(source.text("libraries.json", tag)).libraries }
            .onSuccess { remote -> _catalog.value = _catalog.value + remote; refreshStates() }
            .onFailure { Log.i(TAG, "Catálogo remoto no disponible: ${it.message}") }
    }

    private fun installedFile(lang: String) = File(dir, "$lang.json")

    private fun installed(lang: String): InstalledLibrary? = runCatching {
        installedFile(lang).takeIf { it.isFile }?.let { LibraryArchive.json.decodeFromString<InstalledLibrary>(it.readText()) }
    }.getOrNull()

    private fun saveInstalled(lang: String, value: InstalledLibrary) {
        dir.mkdirs()
        installedFile(lang).writeText(LibraryArchive.json.encodeToString(InstalledLibrary.serializer(), value))
    }

    private fun refreshStates() {
        val langs = _catalog.value.keys + (dir.listFiles()?.map { it.nameWithoutExtension } ?: emptyList())
        _states.update { current ->
            langs.associateWith { lang ->
                val running = current[lang]
                if (jobs[lang]?.isActive == true && running != null) return@associateWith running
                val inst = installed(lang)
                val info = _catalog.value[lang]
                when {
                    inst == null || inst.version == 0 -> if (running is LibraryState.Failed) running else LibraryState.NotInstalled
                    else -> LibraryState.Installed(inst.version, inst.books.size, info != null && info.version > inst.version)
                }
            }
        }
    }

    /** Idioma con biblioteca disponible para [tag] ("es-ES" → "es"), o null. */
    fun libraryFor(tag: String): String? = LanguageTags.primary(tag).takeIf { it in _catalog.value }

    /** Descarga e instala (o actualiza) la biblioteca de [lang] en segundo plano. */
    fun download(lang: String) {
        val info = _catalog.value[lang] ?: return
        if (jobs[lang]?.isActive == true) return
        jobs[lang] = appScope.launch(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "library-$lang")
            try {
                if (!source.configured) throw java.io.IOException("Repositorio de paquetes no configurado.")
                set(lang, LibraryState.Downloading(0, info.sizeBytes))
                tmp.deleteRecursively()
                tmp.mkdirs()
                val file = ModelFile(info.asset, info.sizeBytes, info.sha256)
                val result = source.downloader(listOf(file), tag) { it.path }.download(tmp) { p ->
                    set(lang, LibraryState.Downloading(p.downloadedBytes, p.totalBytes))
                }
                if (result is DownloadResult.Failed) throw java.io.IOException(result.message)
                install(lang, File(tmp, info.asset), File(tmp, "staging"))
                set(lang, null)
            } catch (e: CancellationException) {
                set(lang, null)
                throw e
            } catch (e: Exception) {
                if (e is java.io.IOException) Log.w(TAG, "Biblioteca $lang: ${e.message}") else Log.e(TAG, "Biblioteca $lang", e)
                set(lang, LibraryState.Failed(UiText.res(R.string.library_failed, e.message ?: e.javaClass.simpleName)))
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    private suspend fun install(lang: String, zip: File, staging: File) {
        set(lang, LibraryState.Installing(0, 0))
        val manifest = LibraryArchive.unpack(zip, staging)
        val previous = installed(lang) ?: InstalledLibrary(version = 0)
        val done = previous.books.toMutableMap()
        manifest.books.forEachIndexed { i, book ->
            set(lang, LibraryState.Installing(i, manifest.books.size))
            if (done[book.id] == book.sha256) return@forEachIndexed   // igual que lo instalado
            val draft = importer.readFile(File(staging, book.file))
            importer.commit(
                draft,
                title = book.title,
                author = book.authors.joinToString(", "),
                language = manifest.language,
                level = CefrLevel.parse(book.level) ?: draft.document.level ?: CefrLevel.A1,
                stableId = book.id,
            )
            done[book.id] = book.sha256
            // Se guarda el avance: si se corta, el reintento no vuelve a importar lo ya hecho.
            saveInstalled(lang, InstalledLibrary(previous.version, done))
        }
        val keep = manifest.books.map { it.id }.toSet()
        (done.keys - keep).forEach { id -> importer.delete(ID_PREFIX + id); done.remove(id) }
        saveInstalled(lang, InstalledLibrary(manifest.version, done))
    }

    /** Borra los libros de la biblioteca de [lang] (y con ellos su progreso). */
    suspend fun delete(lang: String) {
        jobs[lang]?.cancel()
        withContext(Dispatchers.IO) {
            installed(lang)?.books?.keys?.forEach { importer.delete(ID_PREFIX + it) }
            installedFile(lang).delete()
        }
        set(lang, null)
    }

    /** null = recalcular desde lo instalado. */
    private fun set(lang: String, state: LibraryState?) {
        if (state == null) {
            _states.update { it - lang }
            refreshStates()
        } else {
            _states.update { it + (lang to state) }
        }
    }

    companion object {
        private const val TAG = "LibraryPacks"
        private const val ID_PREFIX = "u-"

        /** Libro instalado desde una biblioteca descargable (no importado a mano por el usuario). */
        fun isLibraryBook(bookId: String): Boolean = bookId.startsWith("${ID_PREFIX}lib-")
    }
}
