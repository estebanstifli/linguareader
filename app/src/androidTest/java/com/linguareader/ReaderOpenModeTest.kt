package com.linguareader

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.core.model.CefrLevel
import com.linguareader.core.model.Passage
import com.linguareader.core.reader.ReaderAudioController
import com.linguareader.core.reader.TtsEngineSelector
import com.linguareader.core.reader.WarmUpPolicy
import com.linguareader.data.content.BookImportService
import com.linguareader.data.prefs.UserPreferencesRepository
import com.linguareader.engine.supertonic.SupertonicModel
import com.linguareader.ui.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.component.KoinComponent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ReaderOpenModeTest : KoinComponent {
    private class RecordingAudio(val delegate: ReaderAudioController) : ReaderAudioController by delegate {
        val loaded = AtomicInteger()
        val warmed = AtomicInteger()
        val prefetched = AtomicInteger()
        val played = AtomicInteger()
        override suspend fun load(passage: Passage, languageTag: String, voiceId: String?, startOffset: Int) {
            delegate.load(passage, languageTag, voiceId, startOffset)
            loaded.incrementAndGet()
        }
        override suspend fun warmUp(passage: Passage, languageTag: String, voiceId: String?, policy: WarmUpPolicy, fromSentence: Int): Boolean {
            warmed.incrementAndGet()
            return true
        }
        override suspend fun prefetch(passages: List<Passage>, languageTag: String, voiceId: String?) { prefetched.incrementAndGet() }
        override suspend fun play() { played.incrementAndGet() }
    }

    @Test fun readDefersAudioUntilPlayAndListenPreparesOnOpen() = runBlocking {
        val k = getKoin()
        val preferences = k.get<UserPreferencesRepository>()
        val original = preferences.preferences.first()
        val importer = k.get<BookImportService>()
        val store = ViewModelStore()
        var bookId: String? = null
        try {
            preferences.update { it.copy(ttsEngineId = SupertonicModel.ENGINE_ID, ttsMode = true, readingLanguage = "es") }
            withContext(Dispatchers.Main) { k.get<TtsEngineSelector>().selectEngine(SupertonicModel.ENGINE_ID) }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "reader-open-mode.txt").apply {
                writeText("Ana abre un libro y lee junto a la ventana.\n\nDespués sale al jardín para descansar.")
            }
            bookId = importer.commit(importer.read(Uri.fromFile(file)), "Modo de apertura", "", "es", CefrLevel.A2)
            suspend fun open(listen: Boolean): Pair<ReaderViewModel, RecordingAudio> {
                val recording = RecordingAudio(k.get())
                val vm = withContext(Dispatchers.Main) {
                    ReaderViewModel(
                        savedStateHandle = SavedStateHandle(mapOf("bookId" to bookId, "original" to false, "listen" to listen)),
                        books = k.get(), audio = recording, engines = k.get(), engineSelector = k.get(),
                        lookupService = k.get(), preferencesRepository = preferences, progressDao = k.get(),
                        bookmarkDao = k.get(), vocabularyDao = k.get(), voicePacks = k.get(), cue = k.get(),
                        translationModels = k.get(), libraryRepository = k.get(), exporter = k.get(),
                        importedBooks = k.get(), narrations = k.get(), narrationSelector = k.get(), appScope = k.get(),
                    ).also { store.put("reader", it) }
                }
                withTimeout(15_000) { while (recording.loaded.get() == 0) delay(20) }
                return vm to recording
            }
            val (reader, readAudio) = open(false)
            delay(500)
            assertEquals(0, readAudio.warmed.get())
            assertEquals(0, readAudio.prefetched.get())
            assertEquals(0, readAudio.played.get())
            assertFalse(reader.state.value.preparing)
            withContext(Dispatchers.Main) { reader.togglePlay() }.join()
            assertTrue(readAudio.warmed.get() > 0)
            assertEquals(1, readAudio.played.get())
            withContext(Dispatchers.Main) { store.clear() }
            val (_, listenAudio) = open(true)
            withTimeout(20_000) { while (listenAudio.played.get() == 0) delay(20) }
            assertTrue(listenAudio.warmed.get() > 0)
            assertEquals(1, listenAudio.played.get())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            bookId?.let { importer.delete(it) }
            preferences.update { original }
        }
    }
}
