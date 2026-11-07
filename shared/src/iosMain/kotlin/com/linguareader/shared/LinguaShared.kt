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
import com.linguareader.core.engine.TranslationRequest
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.engine.TranslationUnit
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.model.AppPurpose
import com.linguareader.core.model.Book
import com.linguareader.core.model.DisplayMode
import com.linguareader.core.model.HighlightStyle
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.Passage
import com.linguareader.core.model.Sentence
import com.linguareader.core.model.TextJustification
import com.linguareader.core.model.ThemeMode
import com.linguareader.core.model.Token
import com.linguareader.core.platform.epochMillis
import com.linguareader.core.translation.WordLookup
import com.linguareader.data.db.BookmarkEntity
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
import com.linguareader.core.reader.StreamingReaderAudioController
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
import com.linguareader.data.content.ImportDraft
import com.linguareader.data.content.LocalBookImporter
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.library.BookDeleter
import com.linguareader.data.library.LibraryRepository
import com.linguareader.data.library.LibraryState
import com.linguareader.data.narration.NarrationLibrary
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.packs.PackSource
import com.linguareader.engine.packs.LibraryInfo
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.packs.LibraryPackState
import com.linguareader.engine.translation.BookTranslationService
import com.linguareader.engine.translation.BookTranslationState
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.engine.supertonic.SupertonicModelManager
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicStats
import com.linguareader.engine.supertonic.ModelState
import com.linguareader.engine.supertonic.IosMemoryProbe
import com.linguareader.engine.supertonic.iosPhysicalMemoryBytes
import com.linguareader.engine.onnx.IosOnnx
import com.linguareader.core.reader.SentenceClipPipeline
import com.linguareader.core.text.speech.RuleBasedSpeechNormalizer
import com.linguareader.core.text.speech.SwitchableNormalizer
import org.koin.core.qualifier.named
import com.linguareader.engine.supertonic.SupertonicSampleTexts
import com.linguareader.shared.pdf.PdfKitExtractor
import com.linguareader.shared.pdf.PdfWordLayout
import com.linguareader.core.model.OriginalOpenMode
import com.linguareader.core.model.ScreenOrientation
import com.linguareader.data.catalog.CatalogCategory
import com.linguareader.data.catalog.CatalogLanguage
import com.linguareader.data.catalog.CatalogPage
import com.linguareader.data.catalog.LingoOnlineCatalog
import com.linguareader.data.catalog.OnlineBook
import com.linguareader.data.catalog.OnlineBookImporter
import com.linguareader.data.catalog.OnlineCatalog
import com.linguareader.data.catalog.OnlineCatalogFilter
import com.linguareader.engine.supertonic.platformHttpTransport
import com.linguareader.core.engine.EngineReadiness
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
import platform.Foundation.NSProcessInfo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
     * @param packsBaseUrl origen de los paquetes descargables (`https://files.lingoaudiobook.eu`); [packsTag] y [libraryTag] sus prefijos.
     */
    fun start(
        appSupportDir: String,
        cacheDir: String,
        librariesCatalogJson: String?,
        packsBaseUrl: String = PackSource.DEFAULT_BASE_URL,
        packsTag: String = "packs-v1",
        libraryTag: String = "library-v1",
        translator: IosTranslator? = null,
    ) {
        if (koinInstance != null) return
        val root = appSupportDir.toPath()
        appRoot = root
        val cache = cacheDir.toPath()
        cacheRoot = cache
        neuralSupported = IosOnnx.available && iosPhysicalMemoryBytes >= NEURAL_MIN_MEMORY
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
                    // PDF con PDFKit delante de los extractores comunes (mismo hueco que PdfExtractor en Android).
                    single { ExtractorRegistry.default(extra = listOf(PdfKitExtractor())) }
                    single { ImportedBookRepository(fs, root / ImportedBookRepository.DIR, log = { Diagnostics.log("ImportedBooks", it) }) }
                    single { CompositeBookRepository(EmptyBookRepository, get(), get()) } binds arrayOf(BookRepository::class)
                    single { NarrationLibrary(fs, root, get(), log = { Diagnostics.log("Narration", it) }) }
                    single { LocalBookImporter(fs, cache / "import", get(), get(), get()) }
                    single {
                        LibraryRepository(get(), get(), BookDeleter { id ->
                            get<LocalBookImporter>().delete(id)
                            // El PDF original guardado con el libro (iOS lo copia a originals/: los marcadores de Archivos caducan).
                            fs.delete(originalPath(root, id), mustExist = false)
                            pdfLayouts.remove(id)
                        })
                    }
                    single { PackSource(packsBaseUrl, packsTag) }
                    single {
                        LibraryPackManager(
                            fs, root, cache, get(), get(), get(), libraryTag,
                            bundledCatalogJson = { librariesCatalogJson },
                            log = { Diagnostics.log("LibraryPacks", it) },
                        )
                    }

                    // Traducción: glosario del libro y, detrás, el traductor del dispositivo (ML Kit desde Swift).
                    if (translator != null) {
                        single { IosTranslationModels(translator, get(), get()) }
                        single<TranslationModels> { get<IosTranslationModels>() }
                        single<TranslationEngine> {
                            FallbackTranslationEngine(listOf(BundledTranslationEngine(get()), IosTranslationEngine(translator, get(), get())))
                        }
                    } else {
                        single<TranslationEngine> { FallbackTranslationEngine(listOf(BundledTranslationEngine(get()))) }
                        single<TranslationModels> { NoTranslationModels }
                    }
                    single { WordLookupService(get()) }
                    // Catálogo online («Library»), mismo código común que Android, sobre NSURLSession.
                    single<OnlineCatalog> { LingoOnlineCatalog(platformHttpTransport()) }
                    single { OnlineBookImporter(get(), get(), fs, cache / "catalog", platformHttpTransport()) }
                    single { BookTranslationService(get(), get(), get(), get(), log = { Diagnostics.log("BookTranslation", it) }) }

                    // Audio. Dos modos con la misma voz de Apple:
                    // - «Apple voices» (predeterminado): lectura en directo con AVSpeechSynthesizer; los rangos de palabra
                    //   (`willSpeakRangeOfSpeechString`) son fiables en iPhone real (comprobado en Device Farm).
                    // - «Apple voices (cached audio)»: el flujo de Android (frase a frase a la caché, AVAudioEngine); sus
                    //   marcas salen de `write(_:toMarkerCallback:)`, que en los iPhone probados no las da bien. Queda
                    //   como alternativa hasta aclararlo (ver el log «AppleSpeechEngine» del dispositivo).
                    single { AppleSpeechEngine() } binds arrayOf(StreamingSpeechEngine::class, FileSpeechSynthesizer::class)
                    single { CachedAppleVoice(get<AppleSpeechEngine>()) }
                    single { EngineSettings() }
                    single<AudioFocusGate> { AppleAudioFocusGate() }
                    single { FileAudioCache(root / "audio-cache", maxBytesProvider = { get<EngineSettings>().audioCacheBytes }, fileSystem = fs) }
                    single { CachingSpeechSynthesizer(get<CachedAppleVoice>(), get(), log = { Diagnostics.log("AudioCache", it) }) }
                    // Voz neural Supertonic 3 (fase 7), como ttsEngineModule en Android: ONNX Runtime llega por el puente
                    // de la app (pod onnxruntime-objc). Solo se ofrece si el puente está (no en el simulador sin pods) y
                    // el aparato tiene memoria para el modelo; si no, ni se crea.
                    single { SupertonicModelManager(fs, root, get(), log = { Diagnostics.log("SupertonicModel", it) }) }
                    single {
                        val manager = get<SupertonicModelManager>()
                        val settings = get<EngineSettings>()
                        SupertonicEngine(
                            modelDir = { manager.modelDir },
                            steps = { settings.supertonicSteps },
                            memory = IosMemoryProbe,
                            log = { Diagnostics.log("Supertonic", it) },
                            intraOpThreads = NSProcessInfo.processInfo.activeProcessorCount.toInt().coerceIn(1, 4),
                        ).also { engine -> manager.beforeDelete = { engine.release() } }
                    }
                    single(named(NEURAL)) { CachingSpeechSynthesizer(get<SupertonicEngine>(), get(), log = { Diagnostics.log("AudioCache", it) }) }
                    single(named(NEURAL)) {
                        val settings = get<EngineSettings>()
                        SentenceClipPipeline(
                            synthesizer = get<CachingSpeechSynthesizer>(named(NEURAL)),
                            parameters = { mapOf("steps" to settings.supertonicSteps.toString()) },
                            normalizer = SwitchableNormalizer({ settings.normalizeSpeech }, RuleBasedSpeechNormalizer(AppleNumberSpeller)),
                            fileSystem = fs,
                        )
                    }
                    single {
                        TtsEngineRegistry(
                            listOfNotNull(
                                get<StreamingSpeechEngine>(),
                                get<CachingSpeechSynthesizer>(),
                                if (neuralSupported) get<CachingSpeechSynthesizer>(named(NEURAL)) else null,
                            ),
                        )
                    }
                    single {
                        val settings = get<EngineSettings>()
                        val main = Dispatchers.Main
                        val live = StreamingReaderAudioController(get<StreamingSpeechEngine>(), get<AudioFocusGate>(), main) { settings.pauses }
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
                        val cached = get<CachingSpeechSynthesizer>()
                        val neural = if (neuralSupported) {
                            val synthesizer = get<CachingSpeechSynthesizer>(named(NEURAL))
                            synthesizer.id to RenderedReaderAudioController(
                                synthesizer = synthesizer,
                                player = LazyClipPlayer { AudioEngineClipPlayer() },
                                snippetPlayer = LazyClipPlayer { AudioEngineClipPlayer() },
                                dispatcher = main,
                                pauses = { settings.pauses },
                                fileSystem = fs,
                                pipeline = get(named(NEURAL)),
                            )
                        } else {
                            null
                        }
                        SwitchingReaderAudioController(
                            controllers = mapOf(engine.id to live, cached.id to rendered) + listOfNotNull(neural),
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
        Diagnostics.log("App", "shared $VERSION on iOS ${NSProcessInfo.processInfo.operatingSystemVersionString}")
        Diagnostics.log(
            "Supertonic",
            "ONNX Runtime ${if (IosOnnx.available) "linked" else "not linked"}, memory ${iosPhysicalMemoryBytes / 1_000_000} MB, " +
                "${NSProcessInfo.processInfo.activeProcessorCount} cores → neural voice ${if (neuralSupported) "offered" else "not offered"}",
        )
        // Cambios de estado de la reproducción (sin la posición), para saber en el iPhone por qué no suena o no marca.
        koin.get<CoroutineScope>().launch {
            koin.get<ReaderAudioController>().playbackState
                .map { "${it.status}, ${it.granularity}, readiness ${it.readiness}, error ${it.errorCode}, buffering ${it.buffering}, word events ${it.wordEventsObserved}" }
                .distinctUntilChanged()
                .collect { Diagnostics.log("Playback", it) }
        }
        // Preferencias de motor que leen los hilos de audio (pausas, tono, caché, continuación), como TtsEngineCoordinator en Android.
        val settings = koin.get<EngineSettings>()
        koin.get<CoroutineScope>().launch {
            koin.get<UserPreferencesRepository>().preferences.collect { p ->
                settings.pauses = SpeechPauses(p.sentencePauseMs.toLong(), p.paragraphPauseMs.toLong())
                settings.autoContinue = p.autoContinue
                settings.audioCacheBytes = p.audioCacheMb * 1024L * 1024
                settings.ttsPitch = p.ttsPitch
                settings.supertonicSteps = p.supertonicSteps
                settings.normalizeSpeech = p.normalizeSpeech
            }
        }
        // Instalaciones anteriores de iOS guardaban solo readingLanguage y dejaban vacía la lista de idiomas que se aprenden.
        koin.get<CoroutineScope>().launch {
            val repo = koin.get<UserPreferencesRepository>()
            val p = repo.preferences.first()
            if (p.learningLanguages.isEmpty() && p.readingLanguage != null) repo.update { it.withLearningLanguages(listOfNotNull(it.readingLanguage)) }
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
            // Primera apertura en iOS: tema sepia (el lector de Android mantiene el del sistema).
            theme = if (it.purpose == null && it.theme == ThemeMode.SYSTEM) ThemeMode.SEPIA else it.theme,
            libraryGrid = if (it.purpose == null) true else it.libraryGrid,
            ttsMode = !read,
            ttsModeChosen = true,
            learnLanguageChosen = read || it.learnLanguageChosen,
            translationSetupDone = read || it.translationSetupDone,
            learnerLevelChosen = read || it.learnerLevelChosen,
        )
    }

    @Throws(Throwable::class)
    suspend fun setReadingLanguage(tag: String?) = preferences.update {
        // También la lista de idiomas que se aprenden: si solo se escribiera readingLanguage, la clave quedaría vacía.
        it.withLearningLanguages(listOfNotNull(tag)).copy(learnLanguageChosen = true, learnerLevelChosen = tag == null || it.learnerLevelChosen)
    }

    @Throws(Throwable::class)
    suspend fun setTranslationLanguage(tag: String, wifiOnly: Boolean) = preferences.update {
        it.copy(translationLanguage = tag, translationWifiOnly = wifiOnly, translationSetupDone = true)
    }

    // ---- Idiomas que se aprenden (varios a la vez), como LanguagesViewModel + LanguageInstaller en Android

    private val languageInstaller: IosLanguageInstaller get() = IosLanguageInstaller(libraryPacks, translationModels)

    /** Idiomas que se pueden aprender además de los que tienen biblioteca: los que la voz sabe leer (misma lista que Android). */
    fun voiceLanguages(): List<String> = SupertonicModel.LANGUAGES.toList()

    /** Primera apertura: los idiomas que se aprenden, el nativo y «solo Wi-Fi» de una vez; arrancan sus descargas. */
    @Throws(Throwable::class)
    suspend fun chooseLanguages(learning: List<String>, native: String, wifiOnly: Boolean) {
        preferences.update { it.withLanguagesChosen(learning, native, wifiOnly) }
        languageInstaller.installAll(preferences.preferences.first())
    }

    @Throws(Throwable::class)
    suspend fun addLearningLanguage(tag: String) {
        preferences.update { it.withLearningLanguages(it.learningLanguages + tag) }
        languageInstaller.install(tag, preferences.preferences.first())
    }

    /** Quita el idioma del menú; sus libros descargados se conservan (se borran aparte con `libraryPacks.delete`). */
    @Throws(Throwable::class)
    suspend fun removeLearningLanguage(tag: String) = preferences.update { it.withLearningLanguages(it.learningLanguages - tag) }

    @Throws(Throwable::class)
    suspend fun setNativeLanguage(code: String) {
        preferences.update { it.copy(translationLanguage = code) }
        languageInstaller.installAll(preferences.preferences.first())
    }

    @Throws(Throwable::class)
    suspend fun setTranslationWifiOnly(enabled: Boolean) = preferences.update { it.copy(translationWifiOnly = enabled) }

    @Throws(Throwable::class)
    suspend fun setLearnerLevel(level: CefrLevel?) = preferences.update { it.copy(learnerLevel = level, learnerLevelChosen = true) }

    @Throws(Throwable::class)
    suspend fun finishOnboarding() = preferences.update { it.copy(onboardingSummaryShown = true) }

    @Throws(Throwable::class)
    suspend fun setLibraryGrid(grid: Boolean) = preferences.update { it.copy(libraryGrid = grid) }

    @Throws(Throwable::class)
    suspend fun setTtsMode(enabled: Boolean) = preferences.update { it.copy(ttsMode = enabled, ttsModeChosen = true) }

    /** Solo para los tests de UI: vuelve a la primera apertura. */
    @Throws(Throwable::class)
    suspend fun resetOnboarding() = preferences.update {
        it.copy(purpose = null, ttsModeChosen = false, learnLanguageChosen = false, translationSetupDone = false, learnerLevelChosen = false, onboardingSummaryShown = false, readingLanguage = null)
    }

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

    // ---- Lector: progreso, marcadores, vocabulario, traducción al toque y preferencias de lectura

    fun watchBookmarks(bookId: String, block: (List<BookmarkEntity>) -> Unit): FlowWatcher =
        watch(database.bookmarkDao().observeForBook(bookId), block)

    @Throws(Throwable::class)
    suspend fun addBookmark(bookId: String, passageId: String, passageIndex: Int, charOffset: Int, excerpt: String): Long =
        database.bookmarkDao().insert(
            BookmarkEntity(bookId = bookId, passageId = passageId, passageIndex = passageIndex, charOffset = charOffset, excerpt = excerpt, createdAt = epochMillis()),
        )

    @Throws(Throwable::class)
    suspend fun deleteBookmark(id: Long) = database.bookmarkDao().delete(id)

    @Throws(Throwable::class)
    suspend fun saveProgress(bookId: String, passageId: String, passageIndex: Int, totalPassages: Int, charOffset: Int) =
        database.progressDao().upsert(
            ReadingProgressEntity(bookId = bookId, passageId = passageId, passageIndex = passageIndex, totalPassages = totalPassages, charOffset = charOffset, updatedAt = epochMillis()),
        )

    @Throws(Throwable::class)
    suspend fun loadProgress(bookId: String): ReadingProgressEntity? = database.progressDao().get(bookId)

    @Throws(Throwable::class)
    suspend fun lookupWord(book: Book, passage: Passage, token: Token, targetLanguage: String): WordLookup =
        lookup.lookup(book, passage, token, targetLanguage)

    @Throws(Throwable::class)
    suspend fun translateSentence(book: Book, passage: Passage, sentence: Sentence, targetLanguage: String): TranslationResult =
        lookup.translateSentence(book, passage, sentence, targetLanguage)

    @Throws(Throwable::class)
    suspend fun isSavedWord(bookId: String, term: String, targetLanguage: String): Boolean =
        database.vocabularyDao().exists(bookId, term.lowercase(), targetLanguage)

    @Throws(Throwable::class)
    suspend fun saveWord(bookId: String, bookTitle: String, term: String, lemma: String?, translation: String?, sourceLanguage: String, targetLanguage: String, context: String): Long =
        database.vocabularyDao().upsert(
            VocabularyEntity(
                bookId = bookId, bookTitle = bookTitle, term = term.lowercase(), lemma = lemma, translation = translation,
                sourceLanguage = sourceLanguage, targetLanguage = targetLanguage, context = context, createdAt = epochMillis(),
            ),
        )

    @Throws(Throwable::class)
    suspend fun listVoices(languageTag: String): List<VoiceInfo> = speech.listVoices(languageTag)

    @Throws(Throwable::class) suspend fun setDisplayMode(mode: DisplayMode) = preferences.update { it.copy(displayMode = mode) }
    @Throws(Throwable::class) suspend fun setHighlightStyle(style: HighlightStyle) = preferences.update { it.copy(highlightStyle = style) }
    @Throws(Throwable::class) suspend fun setFontScale(scale: Float) = preferences.update { it.copy(fontScale = scale) }
    @Throws(Throwable::class) suspend fun setLineHeight(lineHeight: Float) = preferences.update { it.copy(lineHeight = lineHeight) }
    @Throws(Throwable::class) suspend fun setTheme(theme: ThemeMode) = preferences.update { it.copy(theme = theme) }
    @Throws(Throwable::class) suspend fun setTextJustification(justification: TextJustification) = preferences.update { it.copy(textJustification = justification) }
    @Throws(Throwable::class) suspend fun setAutoScroll(enabled: Boolean) = preferences.update { it.copy(autoScroll = enabled) }
    @Throws(Throwable::class) suspend fun setKeepScreenOn(enabled: Boolean) = preferences.update { it.copy(keepScreenOn = enabled) }
    @Throws(Throwable::class) suspend fun setPauseOnWordTap(enabled: Boolean) = preferences.update { it.copy(pauseOnWordTap = enabled) }
    @Throws(Throwable::class) suspend fun setAutoContinue(enabled: Boolean) = preferences.update { it.copy(autoContinue = enabled) }
    @Throws(Throwable::class) suspend fun setFingerPointer(enabled: Boolean) = preferences.update { it.copy(fingerPointerEnabled = enabled) }
    @Throws(Throwable::class) suspend fun setReduceMotion(enabled: Boolean) = preferences.update { it.copy(reduceMotion = enabled) }
    @Throws(Throwable::class) suspend fun setSpeechRate(rate: Float) = preferences.update { it.copy(speechRate = rate) }
    @Throws(Throwable::class) suspend fun setSentencePause(ms: Int) = preferences.update { it.copy(sentencePauseMs = ms) }
    @Throws(Throwable::class) suspend fun setParagraphPause(ms: Int) = preferences.update { it.copy(paragraphPauseMs = ms) }
    @Throws(Throwable::class) suspend fun setShowIllustrations(enabled: Boolean) = preferences.update { it.copy(showIllustrations = enabled) }
    @Throws(Throwable::class) suspend fun setTtsPitch(pitch: Float) = preferences.update { it.copy(ttsPitch = pitch) }
    @Throws(Throwable::class) suspend fun setAudioCacheLimit(mb: Int) = preferences.update { it.copy(audioCacheMb = mb) }
    @Throws(Throwable::class) suspend fun setDoublePage(enabled: Boolean) = preferences.update { it.copy(doublePage = enabled) }

    /** Orientación por nombre (PORTRAIT, LANDSCAPE, AUTO): Swift no depende de cómo se exporta la entrada AUTO. */
    @Throws(Throwable::class)
    suspend fun setOrientation(name: String) = preferences.update { p ->
        p.copy(orientation = ScreenOrientation.entries.firstOrNull { it.name == name } ?: p.orientation)
    }

    /** Botón del lector: vertical → horizontal → automática, como en Android. */
    @Throws(Throwable::class)
    suspend fun cycleOrientation() = preferences.update { p ->
        p.copy(
            orientation = when (p.orientation) {
                ScreenOrientation.PORTRAIT -> ScreenOrientation.LANDSCAPE
                ScreenOrientation.LANDSCAPE -> ScreenOrientation.AUTO
                ScreenOrientation.AUTO -> ScreenOrientation.PORTRAIT
            },
        )
    }

    /** Bytes que ocupa la caché de audio (sin los clips fijados), como la tarjeta «Caché de audio» de Android. */
    fun audioCacheBytes(): Long = audioCache.sizeBytes()

    fun clearAudioCache() {
        audioCache.clear()
        Diagnostics.log("AudioCache", "cleared from settings")
    }

    /** Frase de muestra en el idioma de la voz (no en el de la interfaz), para «Oír muestra» en Ajustes. */
    fun sampleText(languageTag: String): String {
        val primary = LanguageTags.primary(languageTag)
        return VOICE_SAMPLES[primary] ?: SupertonicSampleTexts.sample(languageTag)
    }

    /**
     * Reproduce la frase de muestra con una voz del sistema (null = automática) sin pasar por el lector: la elige con
     * `prepare` y la encola con `flush`, así que corta la muestra anterior. Devuelve false si el idioma no tiene voz.
     */
    @Throws(Throwable::class)
    suspend fun playVoiceSample(languageTag: String, voiceId: String?, speed: Float): Boolean {
        val readiness = speech.prepare(languageTag, voiceId)
        if (readiness !is EngineReadiness.Ready) return false
        speech.enqueue("sample|${epochMillis()}", sampleText(languageTag), speed, flush = true)
        return true
    }

    fun stopVoiceSample() = speech.stop()

    /** Modo de audio activo: `apple-tts` (lectura en directo) o `apple-tts-cached` (frase a frase a la caché). */
    fun watchSelectedEngine(block: (String) -> Unit): FlowWatcher = watch(engineSelector.selectedEngineId, block)

    @Throws(Throwable::class)
    suspend fun selectEngine(id: String) {
        Diagnostics.log("Audio", "engine → $id")
        engineSelector.selectEngine(id)
    }

    // MARK: Voz neural Supertonic 3 (fase 7), como TtsSettingsViewModel en Android

    /** true si este iPhone puede usar la voz neural: ONNX Runtime enlazado y memoria suficiente (≥ 4 GB). */
    var neuralSupported: Boolean = false
        private set
    val neuralEngineId: String get() = SupertonicModel.ENGINE_ID
    val neuralLicenseName: String get() = SupertonicModel.LICENSE_NAME
    val neuralLicenseUrl: String get() = SupertonicModel.LICENSE_URL
    val neuralDownloadBytes: Long get() = SupertonicModel.TOTAL_BYTES
    private val neuralModel: SupertonicModelManager get() = koin.get()
    private val neuralEngine: SupertonicEngine get() = koin.get()

    fun watchNeuralModel(block: (ModelState) -> Unit): FlowWatcher = watch(neuralModel.state, block)
    fun watchNeuralStats(block: (SupertonicStats) -> Unit): FlowWatcher = watch(neuralEngine.stats, block)

    /** Descarga (o continúa) el modelo, ~400 MB con verificación sha256 por fichero; el estado llega por [watchNeuralModel]. */
    fun downloadNeuralModel() = neuralModel.download()
    fun cancelNeuralDownload() = neuralModel.cancel()
    fun verifyNeuralModel() = neuralModel.verify()

    /** Borra el modelo; si la voz neural era el modo de audio, vuelve a la voz de Apple. */
    @Throws(Throwable::class)
    suspend fun deleteNeuralModel() {
        if (engineSelector.selectedEngineId.value == SupertonicModel.ENGINE_ID) engineSelector.selectEngine(speech.id)
        neuralModel.delete()
    }

    @Throws(Throwable::class) suspend fun setNeuralVoice(voice: String) = preferences.update { it.copy(supertonicVoice = voice) }
    @Throws(Throwable::class) suspend fun setNeuralSteps(steps: Int) = preferences.update { it.copy(supertonicSteps = steps.coerceIn(2, 16)) }
    @Throws(Throwable::class) suspend fun setNormalizeSpeech(enabled: Boolean) = preferences.update { it.copy(normalizeSpeech = enabled) }

    /**
     * Prueba y medición en el iPhone (el «spike» de la fase 7): sintetiza la frase de muestra del idioma con la voz
     * neural (la primera vez carga el modelo) y devuelve tiempos, factor de tiempo real y memoria, con el WAV para
     * oírlo. Se registra también en Diagnostics.
     */
    @Throws(Throwable::class)
    suspend fun testNeuralVoice(languageTag: String, voiceId: String?): NeuralVoiceTest {
        val target = cacheRoot / "neural-voice-test.wav"
        val request = SpeechRequest(
            text = SupertonicSampleTexts.sample(languageTag), languageTag = languageTag, voiceId = voiceId, speed = 1f,
            parameters = mapOf("steps" to koin.get<EngineSettings>().supertonicSteps.toString()),
        )
        val outcome = neuralEngine.synthesizeTo(request, target)
        val stats = neuralEngine.stats.value
        val m = stats.lastSynthesis
        val result = when {
            outcome is SynthesisOutcome.Written && m != null -> NeuralVoiceTest(
                loadMs = m.loadMs ?: -1, synthMs = m.synthMs, audioMs = m.audioMs, steps = m.steps,
                memoryMb = m.memoryMb ?: stats.lastLoad?.memoryAfterMb ?: -1, threads = stats.lastLoad?.threads ?: 0,
                wavPath = target.toString(), error = null,
            )
            outcome is SynthesisOutcome.Failed -> NeuralVoiceTest(error = outcome.failure.message.ifBlank { outcome.failure.reason.name })
            else -> NeuralVoiceTest(error = stats.lastError ?: "No audio")
        }
        Diagnostics.log("Supertonic", "voice test ($languageTag): $result")
        return result
    }

    /** Voces de la voz neural para un idioma (F1–F5, M1–M5). */
    @Throws(Throwable::class)
    suspend fun neuralVoices(languageTag: String): List<VoiceInfo> = neuralEngine.listVoices(languageTag)

    /** Registro de diagnóstico en pantalla (Ajustes › Acerca de › Diagnostics): texto completo con cada cambio. */
    fun watchDiagnostics(block: (String) -> Unit): FlowWatcher = watch(Diagnostics.text, block)
    fun clearDiagnostics() = Diagnostics.clear()
    fun diagnosticsText(): String = Diagnostics.text.value
    fun log(tag: String, message: String) = Diagnostics.log(tag, message)

    /** Voz del sistema elegida para un idioma (null = automática). */
    @Throws(Throwable::class)
    suspend fun setVoice(languageTag: String, voiceId: String?) = preferences.update { p ->
        val key = LanguageTags.primary(languageTag)
        p.copy(voiceByLanguage = if (voiceId == null) p.voiceByLanguage - key else p.voiceByLanguage + (key to voiceId))
    }

    /** Síntesis a fichero con la voz del sistema (tests y muestras): WAV en [targetPath] y marcas de palabra. */
    @Throws(Throwable::class)
    suspend fun synthesizeToFile(text: String, languageTag: String, targetPath: String, speed: Float = 1.0f): SynthesisOutcome =
        koin.get<FileSpeechSynthesizer>().synthesizeTo(SpeechRequest(text, languageTag, null, speed), targetPath.toPath())

    // MARK: Traducción automática (null si la app no pasó un traductor en start()).

    val translationModels: IosTranslationModels? get() = koin.getOrNull()

    /** Idiomas soportados, descargados y descargas en curso; null si no hay traductor. */
    fun watchTranslationModels(block: (TranslationModelsState) -> Unit): FlowWatcher? = translationModels?.let { watch(it.state, block) }

    /** Código del traductor para una etiqueta BCP-47 ("es-ES" → "es"), o null si no lo traduce. */
    fun translationCode(languageTag: String): String? = translationModels?.code(languageTag)

    fun downloadTranslationModel(code: String) { translationModels?.download(code) }

    @Throws(Throwable::class)
    suspend fun deleteTranslationModel(code: String) { translationModels?.delete(code) }

    /** Descarga en segundo plano lo que falte para traducir del idioma del libro al del usuario (onboarding y ajustes). */
    fun downloadTranslationModelsFor(sourceLanguage: String?, targetLanguage: String) {
        val models = translationModels ?: return
        koin.get<CoroutineScope>().launch {
            listOfNotNull(sourceLanguage, targetLanguage).flatMap { models.missingFor(it, targetLanguage) }.distinct().forEach { models.download(it) }
        }
    }

    /** Descarga lo que falte y espera; true si ya se puede traducir de [sourceLanguage] a [targetLanguage]. */
    @Throws(Throwable::class)
    suspend fun ensureTranslationModels(sourceLanguage: String, targetLanguage: String): Boolean {
        val models = translationModels ?: return false
        return models.missingFor(sourceLanguage, targetLanguage).all { models.awaitDownload(it) }
    }

    /** Traduce un texto suelto con el mismo motor que el lector (tests y muestras). */
    @Throws(Throwable::class)
    suspend fun translateText(text: String, sourceLanguage: String, targetLanguage: String): TranslationResult =
        koin.get<TranslationEngine>().translate(
            TranslationRequest(text = text, contextBefore = "", contextAfter = "", sourceLanguage = sourceLanguage, targetLanguage = targetLanguage, unit = TranslationUnit.SENTENCE),
        )

    // MARK: Pretraducción de libros (BookTranslationService, misma lógica que Android)

    val bookTranslations: BookTranslationService get() = koin.get()
    val importedBooks: ImportedBookRepository get() = koin.get()

    /** Traducciones completas en marcha, por id de libro. */
    fun watchBookTranslations(block: (Map<String, BookTranslationState>) -> Unit): FlowWatcher = watch(bookTranslations.states, block)

    /** Por libro importado: idiomas a los que ya está traducido entero y traducción en pausa, si la hay. */
    fun watchTranslationStatus(block: (Map<String, ImportedBookRepository.TranslationStatus>) -> Unit): FlowWatcher =
        watch(importedBooks.translationStatus, block)

    fun startBookTranslation(bookId: String, target: String) { bookTranslations.start(bookId, target) }
    fun cancelBookTranslation(bookId: String) = bookTranslations.cancel(bookId)
    fun dismissBookTranslationError(bookId: String) { bookTranslations.dismissError(bookId) }

    /** Libro importado por el usuario o instalado desde una biblioteca (en iOS, todos los libros). */
    fun isImportedBook(bookId: String): Boolean = importedBooks.owns(bookId)

    // MARK: Importar documentos del usuario (fichero, página web, texto), como BookImportService + ImportViewModel

    private val importService: IosImportService get() = IosImportService(importer)

    /** Extensiones que el importador reconoce (sin punto), para el selector de archivos y los mensajes. */
    fun supportedImportExtensions(): List<String> = koin.get<ExtractorRegistry>().supportedExtensions.toList()

    /** Fichero ya copiado a la caché de la app por Swift (selector de archivos, «Abrir en…»). */
    suspend fun readDocument(path: String, displayName: String, mimeType: String?): ImportOutcome =
        importService.readDocument(path, displayName, mimeType)

    /** Página web (http/https): la única importación con red, iniciada por el usuario. */
    suspend fun readWebPage(url: String): ImportOutcome = importService.readWebPage(url)

    /** Texto pegado. */
    suspend fun readText(text: String, title: String?): ImportOutcome = importService.readText(text, title)

    /** Idiomas a ofrecer en la revisión: el detectado (o el de lectura) primero y después los habituales. */
    suspend fun importLanguageOptions(detected: String?): List<String> {
        val prefs = preferences.preferences.first()
        val first = detected ?: prefs.readingLanguage ?: "en"
        return (listOf(first) + IosImportService.COMMON_LANGUAGES).distinctBy { it.lowercase() }
    }

    /**
     * Guarda el borrador revisado y devuelve el id del libro; con [translateNow] arranca la traducción completa al
     * idioma del usuario si el par se puede traducir en el dispositivo (como `ImportViewModel.confirm`).
     */
    @Throws(Throwable::class)
    suspend fun commitImport(draft: ImportDraft, title: String, author: String, language: String, level: CefrLevel, translateNow: Boolean): String {
        val id = importer.commit(draft, title, author, language, level)
        // PDF: el fichero original pasa de la caché a originals/<id>.pdf (se borra con el libro).
        draft.original?.let { original ->
            val fs = koin.get<FileSystem>()
            val target = originalPath(appRoot, id)
            fs.createDirectories(target.parent!!)
            runCatching { fs.copy(original.uri.toPath(), target) }
                .onSuccess { importedBooks.updateOriginal(id, target.toString()) }
                .onFailure { Diagnostics.log("Import", "could not keep the original PDF: ${it.message}") }
            pdfLayouts.remove(id)
        }
        if (translateNow) {
            val target = preferences.preferences.first().translationLanguage
            if (!LanguageTags.sameLanguage(language, target) && translationModels?.code(language) != null) bookTranslations.start(id, target)
        }
        return id
    }

    // MARK: Catálogo online («Library»), como OnlineLibraryViewModel en Android

    val onlineCatalog: OnlineCatalog get() = koin.get()
    private val onlineImporter: OnlineBookImporter get() = koin.get()

    @Throws(Throwable::class) suspend fun catalogBooks(filter: OnlineCatalogFilter, page: Int): CatalogPage = onlineCatalog.books(filter, page)
    @Throws(Throwable::class) suspend fun catalogBook(id: Int): OnlineBook = onlineCatalog.book(id)
    @Throws(Throwable::class) suspend fun catalogCategories(): List<CatalogCategory> = onlineCatalog.categories()
    @Throws(Throwable::class) suspend fun catalogLanguages(): List<CatalogLanguage> = onlineCatalog.languages()

    /** Portada de un libro del catálogo (≤ 5 MB), o null si falla. */
    suspend fun catalogCover(url: String): NSData? = runCatching { onlineCatalog.fetch(url, 5L * 1024 * 1024) }.getOrNull()?.takeIf { it.isNotEmpty() }?.toNSData()

    /** Id local del libro si ya está en la biblioteca. */
    @Throws(Throwable::class)
    suspend fun catalogLocalId(book: OnlineBook): String? = onlineImporter.bookId(onlineCatalog, book).takeIf { onlineImporter.exists(onlineCatalog, book) }

    /** Descarga e importa el libro (tras confirmarlo el usuario); devuelve el id local. */
    @Throws(Throwable::class)
    suspend fun addCatalogBook(book: OnlineBook, level: CefrLevel): String = onlineImporter.add(onlineCatalog, book, level)

    // MARK: PDF original (visor y «Ver original» en el lector), como PdfViewerViewModel + PdfTextLayouts en Android

    private lateinit var appRoot: okio.Path
    private lateinit var cacheRoot: okio.Path
    private const val NEURAL = "supertonic"
    /** iPhones de 4 GB declaran algo menos (3,7–3,9 GB); los de 3 GB se quedan fuera (jetsam ≈ 1,4 GB). */
    private const val NEURAL_MIN_MEMORY = 3_400_000_000L
    private val pdfLayouts = HashMap<String, PdfWordLayout>()

    private fun originalPath(root: okio.Path, bookId: String): okio.Path = root / "originals" / "$bookId.pdf"

    /** Ruta del PDF original de un libro importado, o null si el libro no es un PDF o el fichero ya no está. */
    @Throws(Throwable::class)
    suspend fun originalPdfPath(bookId: String): String? {
        val original = importedBooks.original(bookId) ?: return null
        if (!original.viewable) return null
        val path = original.uri.toPath()
        return if (koin.get<FileSystem>().exists(path)) path.toString() else null
    }

    /**
     * Cajas de los tokens del libro sobre las páginas del PDF (una vez por sesión y libro; PDFKit + `WordBoxAligner`).
     * null si el libro no tiene PDF original. [onProgress] páginas analizadas / total.
     */
    @Throws(Throwable::class)
    suspend fun pdfWordLayout(bookId: String, onProgress: (Int, Int) -> Unit): PdfWordLayout? {
        pdfLayouts[bookId]?.let { return it }
        val path = originalPdfPath(bookId) ?: return null
        val book = books.loadBook(bookId)
        val layout = PdfWordLayout.compute(book, path, onProgress)
        Diagnostics.log("Pdf", "layout for $bookId: ${layout.tokenCount} tokens, coverage ${(layout.coverage(book) * 100).toInt()} %")
        pdfLayouts[bookId] = layout
        return layout
    }

    @Throws(Throwable::class) suspend fun setOriginalOpenMode(mode: OriginalOpenMode) = preferences.update { it.copy(originalOpenMode = mode) }

    // MARK: Narración pregrabada (mp3 + tiempos por palabra), como en ReaderViewModel y BookDetailViewModel

    /** Narración que declara el libro: lista (descargada y casada con el texto), descargándose, y bytes que ocupa. */
    class NarrationInfo(val ready: Boolean, val downloading: Boolean, val bytes: Long)

    @Throws(Throwable::class)
    suspend fun narrationInfo(bookId: String): NarrationInfo? {
        val book = runCatching { books.loadBook(bookId) }.getOrNull() ?: return null
        val ref = narrations.declared(book) ?: return null
        val bytes = (ref.audio.sizeBytes ?: 0L) + (ref.timings.sizeBytes ?: 0L)
        if (narrations.needsDownload(book)) return NarrationInfo(ready = false, downloading = bookId in narrations.downloading.value, bytes = bytes)
        // Descargada pero sin casar con el texto: no sirve (se lee con la voz, como cualquier libro).
        if (narrations.cached(book) == null) return null
        return NarrationInfo(ready = true, downloading = false, bytes = bytes)
    }

    /** Libros cuya narración se está descargando ahora. */
    fun watchNarrationDownloads(block: (Set<String>) -> Unit): FlowWatcher = watch(narrations.downloading, block)

    /** Id del libro cuya narración acaba de quedar descargada. */
    fun watchNarrationReady(block: (String) -> Unit): FlowWatcher = watch(narrations.ready, block)

    @Throws(Throwable::class)
    suspend fun downloadNarration(bookId: String, retry: Boolean) { narrations.download(books.loadBook(bookId), retry) }

    /**
     * Lector: lee con la narración si está descargada y casa con el texto; si no la hay y el libro la declara, con
     * [download] la pide en segundo plano (el lector cambia a ella en la siguiente pausa). Devuelve si queda activa.
     */
    @Throws(Throwable::class)
    suspend fun applyNarration(book: Book, download: Boolean): Boolean {
        val narration = narrations.cached(book)
        narrationSelector.setNarration(narration)
        if (narration == null && download && narrations.needsDownload(book)) narrations.download(book)
        return narration != null
    }

    /** Versión del código compartido que ve la app (para la pantalla «Acerca de» y los logs de Device Farm). */
    const val VERSION = "0.4.3-ios"

    // Frases de prueba en el idioma de la voz (las mismas que SystemTtsProbe en Android).
    private val VOICE_SAMPLES = mapOf(
        "en" to "The little fox runs through the quiet forest every morning.",
        "es" to "El pequeño zorro corre por el bosque tranquilo cada mañana.",
        "fr" to "Le petit renard court dans la forêt tranquille chaque matin.",
        "de" to "Der kleine Fuchs läuft jeden Morgen durch den stillen Wald.",
        "it" to "La piccola volpe corre nel bosco tranquillo ogni mattina.",
        "pt" to "A pequena raposa corre pela floresta tranquila todas as manhãs.",
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/** Sin traductor automático en iOS todavía: la pretraducción informa de que el par no está soportado. */
private object NoTranslationModels : TranslationModels {
    override fun code(languageTag: String): String? = null
    override suspend fun awaitDownload(code: String): Boolean = false
}
