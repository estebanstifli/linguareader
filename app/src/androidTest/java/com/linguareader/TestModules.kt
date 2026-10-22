package com.linguareader

import androidx.room.Room
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.SpeechSynthesizer
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

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

/** Sustituye el motor del sistema por el falso y la base de datos por una en memoria. */
val testModule = module {
    single<StreamingSpeechEngine> { FakeSpeechEngine() }
    single<AppDatabase> { Room.inMemoryDatabaseBuilder(androidContext(), AppDatabase::class.java).build() }
}
