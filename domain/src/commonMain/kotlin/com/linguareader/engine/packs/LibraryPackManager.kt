package com.linguareader.engine.packs

import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.platform.ioDispatcher
import com.linguareader.data.content.LocalBookImporter
import com.linguareader.engine.supertonic.DownloadResult
import com.linguareader.engine.supertonic.ModelFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * Bibliotecas de libros graduados por idioma: un zip de EPUB por idioma en la release [tag]
 * (tools/build_library.py). Al instalar, cada EPUB se importa como libro del usuario con un id estable
 * (`u-lib-<idioma>-<slug>`), así que actualizar la biblioteca conserva el progreso, los marcadores y el
 * vocabulario; los libros retirados en la versión nueva se borran.
 *
 * El catálogo (`libraries.json`) viene con la app ([bundledCatalogJson]) y se refresca desde la release, para que
 * las bibliotecas nuevas o ampliadas lleguen sin actualizar la app.
 */
class LibraryPackManager(
    private val fileSystem: FileSystem,
    /** Carpeta persistente de la app; el estado de cada biblioteca va en `<filesDir>/libraries/<lang>.json`. */
    filesDir: Path,
    private val cacheDir: Path,
    private val appScope: CoroutineScope,
    private val source: GithubPackSource,
    private val importer: LocalBookImporter,
    private val tag: String,
    bundledCatalogJson: () -> String?,
    private val log: (String) -> Unit = {},
) {
    private val dir = filesDir / "libraries"
    private val _catalog = MutableStateFlow(bundledCatalog(bundledCatalogJson()))
    val catalog: StateFlow<Map<String, LibraryInfo>> = _catalog.asStateFlow()
    private val _states = MutableStateFlow<Map<String, LibraryPackState>>(emptyMap())
    val states: StateFlow<Map<String, LibraryPackState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    init {
        refreshStates()
        appScope.launch(ioDispatcher) { refreshCatalog() }
    }

    private fun bundledCatalog(json: String?): Map<String, LibraryInfo> = runCatching {
        LibraryArchive.json.decodeFromString<LibraryCatalog>(json ?: error("sin catálogo")).libraries
    }.getOrElse { log("Sin catálogo de bibliotecas incluido: ${it.message}"); emptyMap() }

    /** Catálogo de la release (versiones más nuevas que las de la app); sin red se queda el incluido. */
    suspend fun refreshCatalog() {
        if (!source.configured) return
        runCatching { LibraryArchive.json.decodeFromString<LibraryCatalog>(source.text("libraries.json", tag)).libraries }
            .onSuccess { remote -> _catalog.value = _catalog.value + remote; refreshStates() }
            .onFailure { log("Catálogo remoto no disponible: ${it.message}") }
    }

    private fun installedFile(lang: String) = dir / "$lang.json"

    private fun installed(lang: String): InstalledLibrary? = runCatching {
        installedFile(lang).takeIf { fileSystem.exists(it) }
            ?.let { LibraryArchive.json.decodeFromString<InstalledLibrary>(fileSystem.read(it) { readUtf8() }) }
    }.getOrNull()

    private fun saveInstalled(lang: String, value: InstalledLibrary) {
        fileSystem.createDirectories(dir)
        fileSystem.write(installedFile(lang)) { writeUtf8(LibraryArchive.json.encodeToString(InstalledLibrary.serializer(), value)) }
    }

    private fun refreshStates() {
        val onDisk = fileSystem.listOrNull(dir).orEmpty().map { it.name.substringBeforeLast('.') }
        val langs = _catalog.value.keys + onDisk
        _states.update { current ->
            langs.associateWith { lang ->
                val running = current[lang]
                if (jobs[lang]?.isActive == true && running != null) return@associateWith running
                val inst = installed(lang)
                val info = _catalog.value[lang]
                when {
                    inst == null || inst.version == 0 -> if (running is LibraryPackState.Failed) running else LibraryPackState.NotInstalled
                    else -> LibraryPackState.Installed(inst.version, inst.books.size, info != null && info.version > inst.version)
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
        jobs[lang] = appScope.launch(ioDispatcher) {
            val tmp = cacheDir / "library-$lang"
            try {
                if (!source.configured) throw IOException("Repositorio de paquetes no configurado.")
                set(lang, LibraryPackState.Downloading(0, info.sizeBytes))
                fileSystem.deleteRecursively(tmp, mustExist = false)
                fileSystem.createDirectories(tmp)
                val file = ModelFile(info.asset, info.sizeBytes, info.sha256)
                val result = source.downloader(listOf(file), tag) { it.path }.download(tmp) { p ->
                    set(lang, LibraryPackState.Downloading(p.downloadedBytes, p.totalBytes))
                }
                if (result is DownloadResult.Failed) throw IOException(result.message)
                install(lang, tmp / info.asset, tmp / "staging")
                set(lang, null)
            } catch (e: CancellationException) {
                set(lang, null)
                throw e
            } catch (e: Exception) {
                log("Biblioteca $lang: ${e.message ?: e::class.simpleName}")
                set(lang, LibraryPackState.Failed(PackError.LibraryFailed(e.message ?: e::class.simpleName.orEmpty())))
            } finally {
                runCatching { fileSystem.deleteRecursively(tmp, mustExist = false) }
            }
        }
    }

    private suspend fun install(lang: String, zip: Path, staging: Path) {
        set(lang, LibraryPackState.Installing(0, 0))
        val manifest = LibraryArchive.unpack(zip, staging, fileSystem)
        val previous = installed(lang) ?: InstalledLibrary(version = 0)
        val done = previous.books.toMutableMap()
        manifest.books.forEachIndexed { i, book ->
            set(lang, LibraryPackState.Installing(i, manifest.books.size))
            if (done[book.id] == book.sha256) return@forEachIndexed // igual que lo instalado
            val draft = importer.readFile(staging / book.file)
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
        withContext(ioDispatcher) {
            installed(lang)?.books?.keys?.forEach { importer.delete(ID_PREFIX + it) }
            fileSystem.delete(installedFile(lang), mustExist = false)
        }
        set(lang, null)
    }

    /** null = recalcular desde lo instalado. */
    private fun set(lang: String, state: LibraryPackState?) {
        if (state == null) {
            _states.update { it - lang }
            refreshStates()
        } else {
            _states.update { it + (lang to state) }
        }
    }

    companion object {
        private const val ID_PREFIX = "u-"

        /** Libro instalado desde una biblioteca descargable (no importado a mano por el usuario). */
        fun isLibraryBook(bookId: String): Boolean = bookId.startsWith("${ID_PREFIX}lib-")
    }
}
