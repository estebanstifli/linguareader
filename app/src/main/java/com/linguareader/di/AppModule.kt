package com.linguareader.di

import okio.FileSystem
import com.linguareader.data.library.BookDeleter
import com.linguareader.data.content.LocalBookImporter
import com.linguareader.BuildConfig
import android.content.Context
import android.util.Log
import androidx.room.Room
import com.linguareader.core.audio.CachingSpeechSynthesizer
import com.linguareader.core.audio.FileAudioCache
import com.linguareader.core.audio.FileSpeechSynthesizer
import com.linguareader.core.engine.AlignmentEngine
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.TranslationEngine
import com.linguareader.core.engine.UnavailableAlignmentEngine
import com.linguareader.core.narration.NarrationSelector
import com.linguareader.core.reader.NarratedReaderAudioController
import com.linguareader.core.reader.ReaderAudioController
import com.linguareader.core.reader.RenderedReaderAudioController
import com.linguareader.core.reader.SentenceClipPipeline
import com.linguareader.core.reader.SpeechPauses
import com.linguareader.core.reader.StreamingReaderAudioController
import com.linguareader.core.reader.SwitchingReaderAudioController
import com.linguareader.core.reader.TtsEngineRegistry
import com.linguareader.core.reader.TtsEngineSelector
import com.linguareader.core.text.speech.RuleBasedSpeechNormalizer
import com.linguareader.core.text.speech.SwitchableNormalizer
import com.linguareader.core.translation.BundledTranslationEngine
import com.linguareader.core.translation.FallbackTranslationEngine
import com.linguareader.core.translation.WordLookupService
import com.linguareader.data.content.AssetBookRepository
import com.linguareader.data.content.BookExporter
import com.linguareader.data.content.BookImportService
import com.linguareader.data.content.CompositeBookRepository
import com.linguareader.data.content.CoverThumbnails
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.content.PdfTextLayouts
import com.linguareader.data.content.PendingImports
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.library.LibraryRepository
import com.linguareader.data.narration.NarrationLibrary
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.data.prefs.userPreferencesStore
import com.linguareader.data.scan.DocumentScanner
import com.linguareader.data.scan.ScanSettingsRepository
import com.linguareader.engine.alignment.RetiredAlignmentModels
import com.linguareader.engine.audio.AndroidAudioFocusGate
import com.linguareader.engine.audio.CuePlayer
import com.linguareader.core.reader.LazyClipPlayer
import com.linguareader.engine.audio.Media3ClipPlayer
import com.linguareader.engine.importing.PdfExtractor
import com.linguareader.engine.packs.PackSource
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.packs.VoicePackManager
import com.linguareader.engine.supertonic.AndroidMemoryProbe
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicModelManager
import com.linguareader.engine.text.AndroidIcuNumberSpeller
import com.linguareader.engine.audio.BookAudioService
import com.linguareader.engine.translation.BookTranslationService
import com.linguareader.engine.translation.MlKitTranslationEngine
import com.linguareader.engine.translation.TranslationModelManager
import com.linguareader.engine.tts.AndroidTtsEngine
import com.linguareader.engine.tts.SystemTtsAutoSelect
import com.linguareader.importer.ExtractorRegistry
import com.linguareader.ui.catalog.BookDetailViewModel
import com.linguareader.ui.catalog.BookEditViewModel
import com.linguareader.ui.catalog.CatalogViewModel
import com.linguareader.ui.catalog.DeviceDocumentsViewModel
import com.linguareader.ui.catalog.ImportViewModel
import com.linguareader.data.catalog.OnlineCatalog
import com.linguareader.data.catalog.LingoOnlineCatalog
import com.linguareader.data.catalog.OnlineBookImporter
import com.linguareader.ui.library.OnlineLibraryViewModel
import com.linguareader.ui.onboarding.OnboardingViewModel
import com.linguareader.ui.reader.OriginalPdfViewModel
import com.linguareader.ui.reader.ReaderViewModel
import com.linguareader.ui.settings.LibraryViewModel
import com.linguareader.ui.settings.PacksViewModel
import com.linguareader.ui.settings.ScanViewModel
import com.linguareader.ui.settings.SettingsViewModel
import com.linguareader.ui.languages.LanguageInstaller
import com.linguareader.ui.languages.LanguagesViewModel
import com.linguareader.ui.settings.TtsSettingsViewModel
import com.linguareader.ui.viewer.PdfViewerViewModel
import com.linguareader.ui.vocabulary.VocabularyViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.binds
import org.koin.dsl.module
import java.io.File
import java.util.Locale

/*
 * Grafo de dependencias (Koin). Elección de adaptadores: sustituir el glosario por un traductor local/remoto, añadir
 * un motor TTS o cambiar la base de datos se hace aquí sin tocar la UI. Los tests instrumentados superponen sus
 * propios módulos (motor de voz falso, base en memoria) desde `LinguaReaderApp.overrideModules()`.
 * Koin es multiplataforma: el mismo grafo servirá para la app iOS con otros adaptadores.
 */

/** Ajustes de motor que se leen en caliente desde hilos de síntesis (los rellena [TtsEngineCoordinator]). */
class EngineSettingsHolder {
    @Volatile var supertonicSteps: Int = SupertonicEngine.DEFAULT_STEPS
    @Volatile var pauses: SpeechPauses = SpeechPauses()
    @Volatile var audioCacheBytes: Long = 256L * 1024 * 1024
    @Volatile var normalizeSpeech: Boolean = true
    @Volatile var autoContinue: Boolean = true
    /** Motor de texto a voz del sistema elegido (paquete) o null para el predeterminado. */
    @Volatile var systemTtsEngine: String? = null
    /** Tono de la voz del sistema (1.0 = normal). */
    @Volatile var ttsPitch: Float = 1.0f
}

/** Datos: base de datos Room (módulo `:data`), preferencias, libros incluidos e importados, biblioteca, escaneo. */
val dataModule = module {
    single<OnlineCatalog> { LingoOnlineCatalog() }
    single { OnlineBookImporter(androidContext(), get(), get()) }
    /** Ámbito de la aplicación para trabajos que sobreviven a las pantallas (descargas, escaneo, pretraducción). */
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single<AppDatabase> { Room.databaseBuilder(androidContext(), AppDatabase::class.java, "linguareader.db").build() }
    single { get<AppDatabase>().progressDao() }
    single { get<AppDatabase>().bookmarkDao() }
    single { get<AppDatabase>().vocabularyDao() }
    single { get<AppDatabase>().machineTranslationDao() }
    single { get<AppDatabase>().libraryDao() }
    single { get<AppDatabase>().deviceDocumentDao() }

    single { UserPreferencesRepository(androidContext().userPreferencesStore) { Locale.getDefault().language } }
    singleOf(::ScanSettingsRepository)
    singleOf(::EngineSettingsHolder)
    singleOf(::PendingImports)

    /** Formatos importables. Un extractor nuevo (PDF, MOBI...) se añade aquí sin tocar la UI. */
    single { ExtractorRegistry.default(extra = listOf(PdfExtractor(androidContext()))) }
    singleOf(::AssetBookRepository)
    single { ImportedBookRepository(FileSystem.SYSTEM, filesDir() / ImportedBookRepository.DIR, log = { Log.w("ImportedBooks", it) }) }
    single { CompositeBookRepository(get<AssetBookRepository>(), get(), get()) } binds arrayOf(BookRepository::class)
    single { NarrationLibrary(FileSystem.SYSTEM, filesDir(), get(), log = { Log.i("Narration", it) }) }
    singleOf(::PdfTextLayouts)
    single { LocalBookImporter(FileSystem.SYSTEM, cacheDir() / "import", get(), get(), get(), normalizeLanguage = BookImportService::normalizeLanguage) }
    singleOf(::BookImportService)
    singleOf(::BookExporter)
    singleOf(::CoverThumbnails)
    single { LibraryRepository(get(), get(), BookDeleter { get<BookImportService>().delete(it) }) }
    singleOf(::DocumentScanner)
    single { PackSource(BuildConfig.PACKS_BASE_URL, BuildConfig.PACKS_TAG) }
    single {
        LibraryPackManager(
            FileSystem.SYSTEM, filesDir(), cacheDir(), get(), get(), get(), BuildConfig.LIBRARY_TAG,
            bundledCatalogJson = { assetText("packs/libraries.json") },
            log = { Log.i("LibraryPacks", it) },
        )
    }
}

/** Carpeta persistente de la app (`files/`) como ruta Okio, para los gestores compartidos. */
private fun org.koin.core.scope.Scope.filesDir() = androidContext().filesDir.toOkioPath()
private fun org.koin.core.scope.Scope.cacheDir() = androidContext().cacheDir.toOkioPath()
private fun org.koin.core.scope.Scope.assetText(path: String): String? =
    runCatching { androidContext().assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

/** Traducción: primero lo incluido en el libro (traducciones revisadas y glosario); si no, ML Kit en el dispositivo. */
val translationModule = module {
    singleOf(::TranslationModelManager)
    single<TranslationEngine> { FallbackTranslationEngine(listOf(BundledTranslationEngine(get()), MlKitTranslationEngine(get(), get()))) }
    single { WordLookupService(get()) }
    single { BookTranslationService(get(), get(), get<TranslationModelManager>(), get(), log = { Log.w("BookTranslation", it) }) }
    single<AlignmentEngine> { UnavailableAlignmentEngine() }
}

/** Motor del sistema (Android TTS). Los tests lo sustituyen por uno falso. */
val speechEngineModule = module {
    singleOf(::AndroidTtsEngine) { bind<StreamingSpeechEngine>() }
}

/** Motores TTS y fachada del lector. Los tiempos vienen de cada sintetizador. */
val ttsEngineModule = module {
    single { SupertonicModelManager(FileSystem.SYSTEM, filesDir(), get(), log = { Log.i("SupertonicModel", it) }) }
    single {
        RetiredAlignmentModels(FileSystem.SYSTEM, filesDir(), log = { Log.i("RetiredAligners", it) })
    }
    single {
        VoicePackManager(
            FileSystem.SYSTEM, filesDir(), cacheDir(), get(), get(), get(),
            catalogJson = { assetText("packs/voices.json") },
            learningLanguage = get<UserPreferencesRepository>().preferences.map { it.readingLanguage },
            log = { Log.w("VoicePack", it) },
        )
    }
    singleOf(::AndroidAudioFocusGate)
    singleOf(::CuePlayer)
    singleOf(::TtsEngineCoordinator)
    singleOf(::SystemTtsAutoSelect)

    single {
        val manager = get<SupertonicModelManager>()
        val settings = get<EngineSettingsHolder>()
        SupertonicEngine(
            modelDir = { manager.modelDir },
            steps = { settings.supertonicSteps },
            // Mirrored Android x86_64 measurements: two threads were ~13% faster than four,
            // with identical PCM. Keep the existing four-thread cap on unmeasured ARM phones.
            intraOpThreads = minOf(
                if (android.os.Build.SUPPORTED_ABIS.orEmpty().firstOrNull() == "x86_64") 2 else 4,
                Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            ),
            memory = AndroidMemoryProbe,
            log = { Log.i("Supertonic", it) },
        ).also { engine -> manager.beforeDelete = { engine.release() } }
    }

    single {
        val context = androidContext()
        val settings = get<EngineSettingsHolder>()
        // Almacenamiento interno persistente (no cacheDir, que Android puede vaciar sin avisar): los segmentos
        // ya generados y alineados se conservan hasta que el LRU los expulse por superar el límite elegido.
        File(context.cacheDir, "tts-audio").deleteRecursively() // caché antigua, sin alineaciones
        FileAudioCache(File(context.filesDir, "audio-cache").toOkioPath(), maxBytesProvider = { settings.audioCacheBytes })
    }

    /** Supertonic con caché de disco. */
    single { CachingSpeechSynthesizer(get<SupertonicEngine>(), get(), log = { Log.i("AudioCache", it) }) }

    single { TtsEngineRegistry(listOf(get<StreamingSpeechEngine>(), get<CachingSpeechSynthesizer>())) }

    /** Frase → WAV → tiempos por palabra con Supertonic; lo comparten el lector y la generación de libros enteros. */
    single {
        val settings = get<EngineSettingsHolder>()
        SentenceClipPipeline(
            synthesizer = get<CachingSpeechSynthesizer>(),
            parameters = { mapOf("steps" to settings.supertonicSteps.toString()) },
            normalizer = speechNormalizer(settings),
        )
    }

    single { switchingController(androidContext(), get(), get(), get(), get(), get(), get(), get()) } binds
        arrayOf(ReaderAudioController::class, TtsEngineSelector::class, NarrationSelector::class)

    /** Audio de un libro entero en segundo plano; espera mientras el lector reproduce. */
    single {
        val reader = get<ReaderAudioController>()
        BookAudioService(
            books = get<BookRepository>(), pipeline = get(), cache = get(), scope = get(),
            busy = { reader.playbackState.map { it.isPlaying } },
            log = { Log.i("BookAudio", it) },
        )
    }
}

private fun speechNormalizer(settings: EngineSettingsHolder) =
    SwitchableNormalizer({ settings.normalizeSpeech }, RuleBasedSpeechNormalizer(AndroidIcuNumberSpeller))

val viewModelModule = module {
    viewModelOf(::OnlineLibraryViewModel)
    viewModelOf(::CatalogViewModel)
    viewModelOf(::ImportViewModel)
    viewModelOf(::BookEditViewModel)
    viewModelOf(::BookDetailViewModel)
    viewModelOf(::DeviceDocumentsViewModel)
    viewModelOf(::OnboardingViewModel)
    viewModelOf(::ReaderViewModel)
    viewModelOf(::OriginalPdfViewModel)
    viewModelOf(::PdfViewerViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::TtsSettingsViewModel)
    viewModelOf(::LanguagesViewModel)
    singleOf(::LanguageInstaller)
    viewModelOf(::LibraryViewModel)
    viewModelOf(::PacksViewModel)
    viewModelOf(::ScanViewModel)
    viewModelOf(::VocabularyViewModel)
}

/** Todos los módulos de la app, en orden. */
val appModules = listOf(dataModule, translationModule, speechEngineModule, ttsEngineModule, viewModelModule)

private fun switchingController(
    context: Context,
    streaming: StreamingSpeechEngine,
    focus: AndroidAudioFocusGate,
    supertonic: CachingSpeechSynthesizer,
    registry: TtsEngineRegistry,
    settings: EngineSettingsHolder,
    cache: FileAudioCache,
    supertonicPipeline: SentenceClipPipeline,
): SwitchingReaderAudioController {
    val main = Dispatchers.Main.immediate
    val normalizer = speechNormalizer(settings)
    val rendered = RenderedReaderAudioController(
        synthesizer = supertonic,
        player = LazyClipPlayer { Media3ClipPlayer(context, handleAudioFocus = true) },
        snippetPlayer = LazyClipPlayer { Media3ClipPlayer(context, handleAudioFocus = false) },
        dispatcher = main,
        pauses = { settings.pauses },
        pipeline = supertonicPipeline,
    )
    // Android TTS lee con el mismo flujo que Supertonic (frase a frase a la caché de disco, con los tiempos por
    // palabra que da el propio motor al generar). Un motor que solo sabe reproducir (el falso de los tests) sigue
    // en streaming.
    val system = (streaming as? FileSpeechSynthesizer)?.let { file ->
        RenderedReaderAudioController(
            synthesizer = CachingSpeechSynthesizer(file, cache, log = { Log.i("AudioCache", it) }),
            player = LazyClipPlayer { Media3ClipPlayer(context, handleAudioFocus = true) },
            snippetPlayer = LazyClipPlayer { Media3ClipPlayer(context, handleAudioFocus = false) },
            dispatcher = main,
            // El tono forma parte de la clave de caché: otro tono es otro audio.
            parameters = { AndroidTtsEngine.pitchParameters(settings.ttsPitch) },
            pauses = { settings.pauses },
            normalizer = normalizer,
        )
    } ?: StreamingReaderAudioController(streaming, focus, main) { settings.pauses }
    return SwitchingReaderAudioController(
        controllers = mapOf(
            streaming.id to system,
            supertonic.id to rendered,
        ),
        engines = registry,
        defaultEngineId = streaming.id,
        dispatcher = main,
        narrated = NarratedReaderAudioController(
            player = LazyClipPlayer { Media3ClipPlayer(context, handleAudioFocus = true) },
            dispatcher = main,
            continuous = { settings.autoContinue },
        ),
    )
}
