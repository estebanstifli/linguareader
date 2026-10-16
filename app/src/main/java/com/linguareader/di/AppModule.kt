package com.linguareader.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.linguareader.core.audio.CachingSpeechSynthesizer
import com.linguareader.core.audio.FileAudioCache
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
import com.linguareader.data.content.AssetBookRepository
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.db.BookmarkDao
import com.linguareader.data.db.ProgressDao
import com.linguareader.data.db.VocabularyDao
import com.linguareader.engine.audio.AndroidAudioFocusGate
import com.linguareader.engine.alignment.AlignmentModelManager
import com.linguareader.engine.alignment.Wav2Vec2ClipAligner
import com.linguareader.engine.audio.Media3ClipPlayer
import com.linguareader.engine.supertonic.AndroidMemoryProbe
import com.linguareader.engine.supertonic.SupertonicEngine
import com.linguareader.engine.supertonic.SupertonicModelManager
import com.linguareader.engine.tts.AndroidTtsEngine
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
}

/**
 * Elección de adaptadores. Sustituir el glosario por un traductor local/remoto (aquí) o añadir
 * motores TTS ([TtsEngineModule]) no requiere tocar la UI.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun bookRepository(impl: AssetBookRepository): BookRepository = impl

    @Provides @Singleton
    fun translationEngine(books: BookRepository): TranslationEngine =
        // Fase 4: añadir aquí un proveedor local por modelos o remoto (sin claves embebidas).
        FallbackTranslationEngine(listOf(BundledTranslationEngine(books)))

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
    ): SwitchingReaderAudioController {
        val main = Dispatchers.Main.immediate
        val rendered = RenderedReaderAudioController(
            synthesizer = supertonic,
            player = Media3ClipPlayer(context, handleAudioFocus = true),
            snippetPlayer = Media3ClipPlayer(context, handleAudioFocus = false),
            dispatcher = main,
            parameters = { mapOf("steps" to settings.supertonicSteps.toString()) },
            pauses = { settings.pauses },
            aligner = aligner,
        )
        return SwitchingReaderAudioController(
            controllers = mapOf(
                streaming.id to StreamingReaderAudioController(streaming, focus, main) { settings.pauses },
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
}
