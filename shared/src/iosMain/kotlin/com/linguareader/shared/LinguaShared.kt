package com.linguareader.shared

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.TranslationEngine
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.reader.AudioFocusGate
import com.linguareader.core.reader.PlaybackState
import com.linguareader.core.reader.ReaderAudioController
import com.linguareader.core.reader.StreamingReaderAudioController
import com.linguareader.core.translation.BundledTranslationEngine
import com.linguareader.core.translation.FallbackTranslationEngine
import com.linguareader.core.translation.WordLookupService
import com.linguareader.data.content.CompositeBookRepository
import com.linguareader.data.content.EmptyBookRepository
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.content.LocalBookImporter
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.library.BookDeleter
import com.linguareader.data.library.LibraryRepository
import com.linguareader.data.library.LibraryState
import com.linguareader.data.narration.NarrationLibrary
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.packs.GithubPackSource
import com.linguareader.engine.packs.LibraryInfo
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.packs.LibraryPackState
import com.linguareader.engine.translation.BookTranslationService
import com.linguareader.engine.translation.TranslationModels
import com.linguareader.importer.ExtractorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.dsl.binds
import org.koin.dsl.module
import platform.Foundation.NSLocale
import platform.Foundation.NSLog
import platform.Foundation.preferredLanguages

/**
 * Punto de entrada de la app iOS (Swift) al código compartido. Swift llama a [start] una vez con las carpetas de
 * la app y después usa [books], [audio], [preferences], [library], [libraryPacks]…; los Flows se observan con los
 * métodos `watch…`.
 *
 * Es el mismo grafo que en Android (módulo `:domain`) con adaptadores de Apple: base Room con el SQLite
 * empaquetado, preferencias en DataStore (`createWithPath`), libros importados e instalados desde las bibliotecas
 * descargables en `<dir>/imported/<id>/`, descargas con `NSURLSession`, voz del sistema con AVSpeechSynthesizer y el
 * `StreamingReaderAudioController` de `core`.
 */
object LinguaShared {

    private var koinInstance: Koin? = null
    private val koin: Koin get() = koinInstance ?: error("LinguaShared.start() must be called first")

    /**
     * @param appSupportDir carpeta persistente de la app (Application Support, excluida de la copia de seguridad).
     * @param cacheDir carpeta de caché (Caches): copias temporales de importación y zips de bibliotecas.
     * @param librariesCatalogJson contenido de `libraries.json` incluido en el bundle (catálogo de bibliotecas).
     * @param packsRepo repositorio GitHub `usuario/repo` con las releases de paquetes; [packsTag] y [libraryTag] sus etiquetas.
     */
    fun start(
        appSupportDir: String,
        cacheDir: String,
        librariesCatalogJson: String?,
        packsRepo: String = "estebanstifli/linguareader",
        packsTag: String = "packs-v1",
        libraryTag: String = "library-v1",
    ) {
        if (koinInstance != null) return
        val root = appSupportDir.toPath()
        val cache = cacheDir.toPath()
        val fs = FileSystem.SYSTEM
        fs.createDirectories(root)
        fs.createDirectories(cache)
        koinInstance = startKoin {
            modules(
                module {
                    single<FileSystem> { fs }
                    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
                    single<AppDatabase> {
                        Room.databaseBuilder<AppDatabase>(name = (root / "linguareader.db").toString())
                            .setDriver(BundledSQLiteDriver())
                            .setQueryCoroutineContext(Dispatchers.IO)
                            .build()
                    }
                    single { get<AppDatabase>().progressDao() }
                    single { get<AppDatabase>().bookmarkDao() }
                    single { get<AppDatabase>().vocabularyDao() }
                    single { get<AppDatabase>().machineTranslationDao() }
                    single { get<AppDatabase>().libraryDao() }
                    single { get<AppDatabase>().deviceDocumentDao() }
                    single {
                        UserPreferencesRepository(
                            PreferenceDataStoreFactory.createWithPath(scope = get()) { root / "user_preferences.preferences_pb" },
                            deviceLanguage = { NSLocale.preferredLanguages.firstOrNull()?.toString()?.substringBefore('-') ?: "en" },
                        )
                    }

                    // Libros: importados e instalados desde bibliotecas (no hay biblioteca empaquetada en iOS).
                    single { ExtractorRegistry.default() }
                    single { ImportedBookRepository(fs, root / ImportedBookRepository.DIR, log = { NSLog("ImportedBooks: %s", it) }) }
                    single { CompositeBookRepository(EmptyBookRepository, get(), get()) } binds arrayOf(BookRepository::class)
                    single { NarrationLibrary(fs, root, get(), log = { NSLog("Narration: %s", it) }) }
                    single { LocalBookImporter(fs, cache / "import", get(), get(), get()) }
                    single { LibraryRepository(get(), get(), BookDeleter { get<LocalBookImporter>().delete(it) }) }
                    single { GithubPackSource(packsRepo, packsTag) }
                    single {
                        LibraryPackManager(
                            fs, root, cache, get(), get(), get(), libraryTag,
                            bundledCatalogJson = { librariesCatalogJson },
                            log = { NSLog("LibraryPacks: %s", it) },
                        )
                    }

                    // Traducción: por ahora solo lo incluido en el libro (glosario); ML Kit llega en el hito 3.
                    single<TranslationEngine> { FallbackTranslationEngine(listOf(BundledTranslationEngine(get()))) }
                    single { WordLookupService(get()) }
                    single<TranslationModels> { NoTranslationModels }
                    single { BookTranslationService(get(), get(), get(), get(), log = { NSLog("BookTranslation: %s", it) }) }

                    // Audio: voz del sistema en streaming (síntesis a fichero con caché en el hito 2).
                    single<StreamingSpeechEngine> { AppleSpeechEngine() }
                    single<ReaderAudioController> { StreamingReaderAudioController(get(), AudioFocusGate.AlwaysGranted, Dispatchers.Main) }
                },
            )
        }.koin
    }

    val books: BookRepository get() = koin.get()
    val audio: ReaderAudioController get() = koin.get()
    val preferences: UserPreferencesRepository get() = koin.get()
    val speech: StreamingSpeechEngine get() = koin.get()
    val importer: LocalBookImporter get() = koin.get()
    val library: LibraryRepository get() = koin.get()
    val libraryPacks: LibraryPackManager get() = koin.get()
    val lookup: WordLookupService get() = koin.get()
    val database: AppDatabase get() = koin.get()

    /** true si no hay ningún libro todavía (la app iOS ofrece entonces descargar una biblioteca o importa la muestra). */
    suspend fun isLibraryEmpty(): Boolean = books.observeCatalog(CatalogFilter()).first().isEmpty()

    /** Importa un fichero (EPUB, TXT, FB2…) a la biblioteca y devuelve el id del libro. */
    @Throws(Throwable::class)
    suspend fun importFile(path: String, displayName: String, language: String?, level: CefrLevel?): String {
        val draft = importer.readFile(path.toPath(), displayName)
        return importer.commit(
            draft,
            title = draft.document.title ?: draft.sourceName,
            author = draft.document.authors.joinToString(", "),
            language = language ?: draft.detectedLanguage ?: "en",
            level = level ?: draft.document.level ?: CefrLevel.B1,
        )
    }

    fun watchCatalog(block: (List<BookSummary>) -> Unit): FlowWatcher =
        watch(books.observeCatalog(CatalogFilter()), block)

    fun watchPlayback(block: (PlaybackState) -> Unit): FlowWatcher = watch(audio.playbackState, block)

    /** Estanterías, colecciones y papelera. */
    fun watchShelves(block: (LibraryState) -> Unit): FlowWatcher = watch(library.state, block)

    /** Catálogo de bibliotecas descargables (idioma → libros y tamaño). */
    fun watchLibraryCatalog(block: (Map<String, LibraryInfo>) -> Unit): FlowWatcher = watch(libraryPacks.catalog, block)

    /** Estado de descarga e instalación de cada biblioteca. */
    fun watchLibraryStates(block: (Map<String, LibraryPackState>) -> Unit): FlowWatcher = watch(libraryPacks.states, block)

    /** Eventos de un motor de voz (inicio, rangos de palabra, fin) para los tests y la UI. */
    fun watchSpeechEvents(engine: StreamingSpeechEngine, block: (SpeechEvent) -> Unit): FlowWatcher = watch(engine.events, block)

    /** Versión del código compartido que ve la app (para la pantalla «Acerca de» y los logs de Device Farm). */
    const val VERSION = "0.3.0-ios"
}

/** Sin traductor automático en iOS todavía: la pretraducción informa de que el par no está soportado. */
private object NoTranslationModels : TranslationModels {
    override fun code(languageTag: String): String? = null
    override suspend fun awaitDownload(code: String): Boolean = false
}
