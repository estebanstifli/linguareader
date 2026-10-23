package com.linguareader.shared

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.audio.SynthesisOutcome
import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.TranslationEngine
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.content.MediaResult
import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.MediaRef
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.db.ReadingProgressEntity
import com.linguareader.data.db.VocabularyEntity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import com.linguareader.core.reader.AudioFocusGate
import com.linguareader.core.reader.PlaybackState
import com.linguareader.core.reader.ReaderAudioController
import com.linguareader.core.audio.CachingSpeechSynthesizer
import com.linguareader.core.audio.FileAudioCache
import com.linguareader.core.audio.FileSpeechSynthesizer
import com.linguareader.core.narration.NarrationSelector
import com.linguareader.core.reader.LazyClipPlayer
import com.linguareader.core.reader.NarratedReaderAudioController
import com.linguareader.core.reader.RenderedReaderAudioController
import com.linguareader.core.reader.SpeechPauses
import com.linguareader.core.reader.SwitchingReaderAudioController
import com.linguareader.core.reader.TtsEngineRegistry
import com.linguareader.core.reader.TtsEngineSelector
import kotlinx.coroutines.launch
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

                    // Audio: el mismo flujo que Android. La voz del sistema sintetiza frase a frase a la caché de disco
                    // (con marcas de palabra) y suena por AVAudioEngine; la narración pregrabada, por AVAudioPlayer.
                    single { AppleSpeechEngine() } binds arrayOf(StreamingSpeechEngine::class, FileSpeechSynthesizer::class)
                    single { EngineSettings() }
                    single<AudioFocusGate> { AppleAudioFocusGate() }
                    single { FileAudioCache(root / "audio-cache", maxBytesProvider = { get<EngineSettings>().audioCacheBytes }, fileSystem = fs) }
                    single { CachingSpeechSynthesizer(get<FileSpeechSynthesizer>(), get(), log = { NSLog("AudioCache: %s", it) }) }
                    single { TtsEngineRegistry(listOf(get<StreamingSpeechEngine>())) }
                    single {
                        val settings = get<EngineSettings>()
                        val main = Dispatchers.Main
                        val rendered = RenderedReaderAudioController(
                            synthesizer = get<CachingSpeechSynthesizer>(),
                            player = LazyClipPlayer { AudioEngineClipPlayer() },
                            snippetPlayer = LazyClipPlayer { AudioEngineClipPlayer() },
                            dispatcher = main,
                            parameters = { AppleSpeechEngine.pitchParameters(settings.ttsPitch) },
                            pauses = { settings.pauses },
                            fileSystem = fs,
                        )
                        val engine = get<StreamingSpeechEngine>()
                        SwitchingReaderAudioController(
                            controllers = mapOf(engine.id to rendered),
                            engines = get(),
                            defaultEngineId = engine.id,
                            dispatcher = main,
                            narrated = NarratedReaderAudioController(
                                player = LazyClipPlayer { TrackClipPlayer() },
                                dispatcher = main,
                                continuous = { settings.autoContinue },
                            ),
                        )
                    } binds arrayOf(ReaderAudioController::class, TtsEngineSelector::class, NarrationSelector::class)
                },
            )
        }.koin
        // Preferencias de motor que leen los hilos de audio (pausas, tono, caché, continuación), como TtsEngineCoordinator en Android.
        val settings = koin.get<EngineSettings>()
        koin.get<CoroutineScope>().launch {
            koin.get<UserPreferencesRepository>().preferences.collect { p ->
                settings.pauses = SpeechPauses(p.sentencePauseMs.toLong(), p.paragraphPauseMs.toLong())
                settings.autoContinue = p.autoContinue
                settings.audioCacheBytes = p.audioCacheMb * 1024L * 1024
                settings.ttsPitch = p.ttsPitch
            }
        }
    }

    val books: BookRepository get() = koin.get()
    val audio: ReaderAudioController get() = koin.get()
    val engineSelector: TtsEngineSelector get() = koin.get()
    val narrationSelector: NarrationSelector get() = koin.get()
    val narrations: NarrationLibrary get() = koin.get()
    val audioCache: FileAudioCache get() = koin.get()
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

    // ---- Preferencias: operaciones concretas para Swift (el `copy` de una data class no se exporta de forma usable).

    fun watchPreferences(block: (UserPreferences) -> Unit): FlowWatcher = watch(preferences.preferences, block)

    /** Primer paso de la primera apertura. READ salta los pasos de idioma y nivel, como en Android. */
    @Throws(Throwable::class)
    suspend fun setPurpose(purpose: AppPurpose) = preferences.update {
        val read = purpose == AppPurpose.READ
        it.copy(
            purpose = purpose,
            ttsMode = !read,
            ttsModeChosen = true,
            learnLanguageChosen = read || it.learnLanguageChosen,
            translationSetupDone = read || it.translationSetupDone,
            learnerLevelChosen = read || it.learnerLevelChosen,
        )
    }

    @Throws(Throwable::class)
    suspend fun setReadingLanguage(tag: String?) = preferences.update {
        it.copy(readingLanguage = tag, learnLanguageChosen = true, learnerLevelChosen = tag == null || it.learnerLevelChosen)
    }

    @Throws(Throwable::class)
    suspend fun setTranslationLanguage(tag: String, wifiOnly: Boolean) = preferences.update {
        it.copy(translationLanguage = tag, translationWifiOnly = wifiOnly, translationSetupDone = true)
    }

    @Throws(Throwable::class)
    suspend fun setLearnerLevel(level: CefrLevel?) = preferences.update { it.copy(learnerLevel = level, learnerLevelChosen = true) }

    @Throws(Throwable::class)
    suspend fun finishOnboarding() = preferences.update { it.copy(onboardingSummaryShown = true) }

    @Throws(Throwable::class)
    suspend fun setLibraryGrid(grid: Boolean) = preferences.update { it.copy(libraryGrid = grid) }

    @Throws(Throwable::class)
    suspend fun setTtsMode(enabled: Boolean) = preferences.update { it.copy(ttsMode = enabled, ttsModeChosen = true) }

    /** Solo para los tests de UI: deja la app como si la primera apertura ya se hubiera completado. */
    @Throws(Throwable::class)
    suspend fun skipOnboarding() = preferences.update {
        it.copy(purpose = AppPurpose.LEARN, ttsModeChosen = true, learnLanguageChosen = true, translationSetupDone = true, learnerLevelChosen = true, onboardingSummaryShown = true)
    }

    // ---- Progreso, vocabulario y catálogo

    fun watchProgress(block: (List<ReadingProgressEntity>) -> Unit): FlowWatcher = watch(database.progressDao().observeAll(), block)

    fun watchVocabulary(block: (List<VocabularyEntity>) -> Unit): FlowWatcher = watch(database.vocabularyDao().observeAll(), block)

    @Throws(Throwable::class)
    suspend fun deleteVocabulary(id: Long) = database.vocabularyDao().delete(id)

    /** Resumen de un libro con las correcciones del usuario aplicadas (pantalla «Editar»). */
    @Throws(Throwable::class)
    suspend fun loadSummary(bookId: String): BookSummary? = koin.get<CompositeBookRepository>().loadSummary(bookId)

    /** Bytes de la portada de un libro (verificados), o null si no hay o está corrupta. */
    @Throws(Throwable::class)
    suspend fun loadCover(bookId: String, cover: MediaRef): NSData? =
        (books.loadMedia(bookId, cover) as? MediaResult.Ok)?.bytes?.takeIf { it.isNotEmpty() }?.toNSData()

    /** Libro instalado desde una biblioteca descargable (no importado a mano). */
    fun isLibraryBook(bookId: String): Boolean = LibraryPackManager.isLibraryBook(bookId)

    /** Texto plano del libro (capítulos y pasajes), para compartirlo. */
    @Throws(Throwable::class)
    suspend fun bookText(bookId: String): String {
        val book = books.loadBook(bookId)
        return buildString {
            appendLine(book.title)
            if (book.authors.isNotEmpty()) appendLine(book.authors.joinToString(", "))
            book.chapters.forEach { chapter ->
                appendLine()
                appendLine(chapter.title)
                chapter.passages.forEach { appendLine(); appendLine(it.text) }
            }
        }
    }

    /** Síntesis a fichero con la voz del sistema (tests y muestras): WAV en [targetPath] y marcas de palabra. */
    @Throws(Throwable::class)
    suspend fun synthesizeToFile(text: String, languageTag: String, targetPath: String, speed: Float = 1.0f): SynthesisOutcome =
        koin.get<FileSpeechSynthesizer>().synthesizeTo(SpeechRequest(text, languageTag, null, speed), targetPath.toPath())

    /** Versión del código compartido que ve la app (para la pantalla «Acerca de» y los logs de Device Farm). */
    const val VERSION = "0.3.0-ios"
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/** Sin traductor automático en iOS todavía: la pretraducción informa de que el par no está soportado. */
private object NoTranslationModels : TranslationModels {
    override fun code(languageTag: String): String? = null
    override suspend fun awaitDownload(code: String): Boolean = false
}
