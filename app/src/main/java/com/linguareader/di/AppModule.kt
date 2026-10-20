package com.linguareader.di

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
import com.linguareader.core.reader.ReaderAudioController
import com.linguareader.core.reader.RenderedReaderAudioController
import com.linguareader.core.reader.SpeechPauses
import com.linguareader.core.reader.StreamingReaderAudioController
import com.linguareader.core.reader.SwitchingReaderAudioController
import com.linguareader.core.reader.TtsEngineRegistry
import com.linguareader.core.reader.TtsEngineSelector
import com.linguareader.core.translation.BundledTranslationEngine
import com.linguareader.core.translation.FallbackTranslationEngine
import com.linguareader.core.translation.WordLookupService
import com.linguareader.data.content.CompositeBookRepository
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.db.BookmarkDao
import com.linguareader.data.db.DeviceDocumentDao
import com.linguareader.data.db.LibraryDao
import com.linguareader.data.db.MachineTranslationDao
import com.linguareader.data.db.ProgressDao
import com.linguareader.data.db.VocabularyDao
import com.linguareader.engine.audio.AndroidAudioFocusGate
import com.linguareader.engine.alignment.AlignmentModelManager
import com.linguareader.engine.alignment.Wav2Vec2ClipAligner
import com.linguareader.engine.audio.LazyClipPlayer
import com.linguareader.engine.audio.Media3ClipPlayer
import com.linguareader.engine.importing.PdfExtractor
import com.linguareader.engine.supertonic.AndroidMemoryProbe
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicModelManager
import com.linguareader.engine.translation.MlKitTranslationEngine
import com.linguareader.engine.translation.TranslationModelManager
import com.linguareader.core.text.speech.RuleBasedSpeechNormalizer
import com.linguareader.core.text.speech.SwitchableNormalizer
import com.linguareader.engine.text.AndroidIcuNumberSpeller
import com.linguareader.engine.tts.AndroidTtsEngine
import com.linguareader.importer.ExtractorRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Ajustes de motor que se leen en caliente desde hilos de síntesis (los rellena [TtsEngineCoordinator]). */
@Singleton
class EngineSettingsHolder @Inject constructor() {
    @Volatile var supertonicSteps: Int = SupertonicEngine.DEFAULT_STEPS
    @Volatile var pauses: SpeechPauses = SpeechPauses()
    @Volatile var audioCacheBytes: Long = 256L * 1024 * 1024
    @Volatile var normalizeSpeech: Boolean = true
}

/**
 * Elección de adaptadores. Sustituir el glosario por un traductor local/remoto (aquí) o añadir
 * motores TTS ([TtsEngineModule]) no requiere tocar la UI.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun bookRepository(impl: CompositeBookRepository): BookRepository = impl

    /** Formatos importables. Un extractor nuevo (PDF, MOBI...) se añade aquí sin tocar la UI. */
    @Provides @Singleton
    fun extractorRegistry(@ApplicationContext context: Context): ExtractorRegistry =
        ExtractorRegistry.default(extra = listOf(PdfExtractor(context)))

    /**
     * Primero lo incluido en el libro (traducciones revisadas y glosario); si no hay, traducción
     * automática en el dispositivo (ML Kit). Un proveedor remoto iría detrás, sin claves embebidas.
     */
    @Provides @Singleton
    fun translationEngine(books: BookRepository, models: TranslationModelManager, cache: MachineTranslationDao): TranslationEngine =
        FallbackTranslationEngine(listOf(BundledTranslationEngine(books), MlKitTranslationEngine(models, cache)))

    @Provides @Singleton
    fun wordLookup(engine: TranslationEngine): WordLookupService = WordLookupService(engine)

    @Provides @Singleton
    fun alignmentEngine(): AlignmentEngine = UnavailableAlignmentEngine()

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Motor del sistema (Android TTS). Reemplazable en tests (@TestInstallIn). */
@Module
@InstallIn(SingletonComponent::class)
object SpeechEngineModule {
    @Provides @Singleton
    fun streamingSpeechEngine(impl: AndroidTtsEngine): StreamingSpeechEngine = impl
}

/** Motores TTS y fachada del lector. Un motor futuro = otro controlador + entrada en el registro. */
@Module
@InstallIn(SingletonComponent::class)
object TtsEngineModule {

    @Provides @Singleton
    fun supertonicEngine(manager: SupertonicModelManager, settings: EngineSettingsHolder): SupertonicEngine =
        SupertonicEngine(
            modelDir = { manager.modelDir },
            steps = { settings.supertonicSteps },
            memory = AndroidMemoryProbe,
            log = { Log.i("Supertonic", it) },
        ).also { engine -> manager.beforeDelete = { engine.release() } }

    @Provides @Singleton
    fun clipAligner(manager: AlignmentModelManager): Wav2Vec2ClipAligner =
        Wav2Vec2ClipAligner(
            modelFor = { language -> manager.filesFor(language) },
            log = { Log.i("WordAlign", it) },
        ).also { aligner -> manager.beforeDelete = { aligner.release() } }

    @Provides @Singleton
    fun audioCache(@ApplicationContext context: Context, settings: EngineSettingsHolder): FileAudioCache {
        // Almacenamiento interno persistente (no cacheDir, que Android puede vaciar sin avisar): los segmentos
        // ya generados y alineados se conservan hasta que el LRU los expulse por superar el límite elegido.
        File(context.cacheDir, "tts-audio").deleteRecursively() // caché antigua, sin alineaciones
        return FileAudioCache(File(context.filesDir, "audio-cache"), maxBytesProvider = { settings.audioCacheBytes })
    }

    @Provides @Singleton
    fun supertonicSynthesizer(engine: SupertonicEngine, cache: FileAudioCache): CachingSpeechSynthesizer =
        CachingSpeechSynthesizer(engine, cache)

    @Provides @Singleton
    fun engineRegistry(streaming: StreamingSpeechEngine, supertonic: CachingSpeechSynthesizer): TtsEngineRegistry =
        TtsEngineRegistry(listOf(streaming, supertonic))

    @Provides @Singleton
    fun switchingController(
        @ApplicationContext context: Context,
        streaming: StreamingSpeechEngine,
        focus: AndroidAudioFocusGate,
        supertonic: CachingSpeechSynthesizer,
        registry: TtsEngineRegistry,
        settings: EngineSettingsHolder,
        aligner: Wav2Vec2ClipAligner,
        cache: FileAudioCache,
    ): SwitchingReaderAudioController {
        val main = Dispatchers.Main.immediate
        val normalizer = SwitchableNormalizer({ settings.normalizeSpeech }, RuleBasedSpeechNormalizer(AndroidIcuNumberSpeller))
        val rendered = RenderedReaderAudioController(
            synthesizer = supertonic,
            player = LazyClipPlayer { Media3ClipPlayer(context, handleAudioFocus = true) },
            snippetPlayer = LazyClipPlayer { Media3ClipPlayer(context, handleAudioFocus = false) },
            dispatcher = main,
            parameters = { mapOf("steps" to settings.supertonicSteps.toString()) },
            pauses = { settings.pauses },
            aligner = aligner,
            normalizer = normalizer,
        )
        // Android TTS lee con el mismo flujo que Supertonic (frase a frase a la caché de disco, con los tiempos por
        // palabra que da el propio motor al generar). Un motor que solo sabe reproducir (el falso de los tests) sigue
        // en streaming.
        val system = (streaming as? FileSpeechSynthesizer)?.let { file ->
            RenderedReaderAudioController(
                synthesizer = CachingSpeechSynthesizer(file, cache),
                player = LazyClipPlayer { Media3ClipPlayer(context, handleAudioFocus = true) },
                snippetPlayer = LazyClipPlayer { Media3ClipPlayer(context, handleAudioFocus = false) },
                dispatcher = main,
                pauses = { settings.pauses },
                aligner = aligner, // solo si el motor instalado no da marcas y hay modelo de alineación
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
        )
    }

    @Provides fun readerAudioController(s: SwitchingReaderAudioController): ReaderAudioController = s
    @Provides fun engineSelector(s: SwitchingReaderAudioController): TtsEngineSelector = s
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "linguareader.db").build()

    @Provides fun progressDao(db: AppDatabase): ProgressDao = db.progressDao()
    @Provides fun bookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun vocabularyDao(db: AppDatabase): VocabularyDao = db.vocabularyDao()
    @Provides fun machineTranslationDao(db: AppDatabase): MachineTranslationDao = db.machineTranslationDao()
    @Provides fun libraryDao(db: AppDatabase): LibraryDao = db.libraryDao()
    @Provides fun deviceDocumentDao(db: AppDatabase): DeviceDocumentDao = db.deviceDocumentDao()
}
