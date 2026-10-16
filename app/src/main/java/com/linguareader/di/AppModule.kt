package com.linguareader.di

import android.content.Context
import androidx.room.Room
import com.linguareader.core.engine.AlignmentEngine
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.engine.SpeechSynthesizer
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.TranslationEngine
import com.linguareader.core.engine.UnavailableAlignmentEngine
import com.linguareader.core.reader.ReaderAudioController
import com.linguareader.core.reader.StreamingReaderAudioController
import com.linguareader.core.translation.BundledTranslationEngine
import com.linguareader.core.translation.FallbackTranslationEngine
import com.linguareader.core.translation.WordLookupService
import com.linguareader.data.content.AssetBookRepository
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.db.BookmarkDao
import com.linguareader.data.db.ProgressDao
import com.linguareader.data.db.VocabularyDao
import com.linguareader.engine.audio.AndroidAudioFocusGate
import com.linguareader.engine.tts.AndroidTtsEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Elección de adaptadores. Sustituir el glosario por un traductor local/remoto (aquí) o Android TTS
 * por Supertonic ([SpeechEngineModule]) no requiere tocar la UI.
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

    @Provides @Singleton
    fun readerAudioController(engine: StreamingSpeechEngine, focus: AndroidAudioFocusGate): ReaderAudioController =
        StreamingReaderAudioController(engine, focus, Dispatchers.Main.immediate)

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Motor de voz. Reemplazable en tests (@TestInstallIn) o por Supertonic en Fase 3. */
@Module
@InstallIn(SingletonComponent::class)
object SpeechEngineModule {
    @Provides @Singleton
    fun streamingSpeechEngine(impl: AndroidTtsEngine): StreamingSpeechEngine = impl

    @Provides @Singleton
    fun speechSynthesizer(impl: AndroidTtsEngine): SpeechSynthesizer = impl
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
