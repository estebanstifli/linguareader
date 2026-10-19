package com.linguareader

import android.content.Context
import androidx.room.Room
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.SpeechSynthesizer
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.data.db.AppDatabase
import com.linguareader.data.db.BookmarkDao
import com.linguareader.data.db.ProgressDao
import com.linguareader.data.db.VocabularyDao
import com.linguareader.di.DatabaseModule
import com.linguareader.di.SpeechEngineModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Singleton

/**
 * Motor TTS falso: al encolar la primera frase emite Started + un evento de palabra y se queda
 * "hablando" (no emite Done), para comprobar el resaltado por palabra sin audio real.
 */
class FakeSpeechEngine : StreamingSpeechEngine, SpeechSynthesizer {
    override val id = "fake"
    override val info = com.linguareader.core.engine.TtsEngineInfo("fake", "Fake", true, true, false, true)
    override val supportsSpeed = true
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SpeechEvent> = _events

    override suspend fun prepare(languageTag: String, voiceId: String?) = EngineReadiness.Ready
    override fun enqueue(utteranceId: String, text: String, speed: Float, flush: Boolean) {
        if (!flush) return
        _events.tryEmit(SpeechEvent.Started(utteranceId))
        val end = text.indexOf(' ').takeIf { it > 0 } ?: text.length
        _events.tryEmit(SpeechEvent.WordRange(utteranceId, 0, end))
    }
    override fun stop() {}
    override suspend fun listVoices(languageTag: String) = emptyList<VoiceInfo>()
    override suspend fun synthesize(request: SpeechRequest): SpeechResult =
        SpeechResult.Failure(EngineUnavailableReason.SYNTHESIS_ERROR, "fake")
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SpeechEngineModule::class])
object FakeSpeechEngineModule {
    private val engine = FakeSpeechEngine()
    @Provides @Singleton fun streaming(): StreamingSpeechEngine = engine
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object InMemoryDatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @Provides fun progressDao(db: AppDatabase): ProgressDao = db.progressDao()
    @Provides fun bookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun vocabularyDao(db: AppDatabase): VocabularyDao = db.vocabularyDao()
    @Provides fun libraryDao(db: AppDatabase): com.linguareader.data.db.LibraryDao = db.libraryDao()
    @Provides fun machineTranslationDao(db: AppDatabase): com.linguareader.data.db.MachineTranslationDao = db.machineTranslationDao()
}
