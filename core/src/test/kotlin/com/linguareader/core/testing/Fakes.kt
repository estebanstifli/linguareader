package com.linguareader.core.testing

import com.linguareader.core.content.BookManifestParser
import com.linguareader.core.content.MediaResult
import com.linguareader.core.engine.BookRepository
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.engine.StreamingSpeechEngine
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.model.Book
import com.linguareader.core.model.BookSummary
import com.linguareader.core.model.CatalogFilter
import com.linguareader.core.model.MediaRef
import com.linguareader.core.model.Passage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf

class InMemoryBookRepository(vararg books: Book) : BookRepository {
    private val byId = books.associateBy { it.id }
    override fun observeCatalog(filter: CatalogFilter): Flow<List<BookSummary>> =
        flowOf(byId.values.map { it.summary() }.filter(filter::matches))
    override suspend fun loadBook(bookId: String): Book = byId[bookId] ?: throw NoSuchElementException(bookId)
    override suspend fun loadPassage(bookId: String, passageId: String): Passage =
        loadBook(bookId).passages.first { it.id == passageId }
    override suspend fun loadMedia(bookId: String, media: MediaRef): MediaResult = MediaResult.Missing
}

/** Motor TTS falso: registra lo encolado; los tests emiten los eventos a mano. */
class FakeStreamingEngine(
    var readiness: EngineReadiness = EngineReadiness.Ready,
    override val supportsSpeed: Boolean = true,
) : StreamingSpeechEngine {
    data class Enqueued(val utteranceId: String, val text: String, val speed: Float, val flush: Boolean)

    override val id = "fake"
    override val info = com.linguareader.core.engine.TtsEngineInfo(
        id = "fake", displayName = "Fake", streaming = true, providesWordTimings = true,
        requiresModelDownload = false, offline = true,
    )
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SpeechEvent> = _events
    val enqueued = mutableListOf<Enqueued>()
    var stopCount = 0

    override suspend fun prepare(languageTag: String, voiceId: String?) = readiness
    override fun enqueue(utteranceId: String, text: String, speed: Float, flush: Boolean) {
        if (flush) enqueued.clear()
        enqueued += Enqueued(utteranceId, text, speed, flush)
    }
    val silences = mutableListOf<Pair<String, Long>>()
    override fun enqueueSilence(utteranceId: String, durationMs: Long) { silences += utteranceId to durationMs }
    override fun stop() { stopCount++; enqueued.clear() }
    override suspend fun listVoices(languageTag: String) = emptyList<VoiceInfo>()

    suspend fun emit(event: SpeechEvent) = _events.emit(event)
}

object SampleBooks {
    val englishJson = """
        {
          "formatVersion": 1, "id": "en-test", "title": "Test", "learningLanguage": "en", "cefrLevel": "A1",
          "license": { "id": "LicenseRef-Test" }, "sourceAttribution": "Test",
          "chapters": [ { "id": "ch1", "title": "One", "passages": [
            { "id": "p1", "sentences": [
              { "id": "s1", "text": "Tom wakes up early.", "translations": { "es": "Tom se despierta temprano." } },
              { "id": "s2", "text": "Don't worry, Anna.", "translations": { "es": "No te preocupes, Anna." } }
            ] } ] } ],
          "glossary": [
            { "id": "wake-up", "lemma": "wake up", "kind": "phrasal_verb", "forms": ["wakes up", "wake up"], "translations": { "es": "despertarse", "fr": "se réveiller" } },
            { "id": "wake", "lemma": "wake", "forms": ["wakes"], "translations": { "es": "despertar" } },
            { "id": "early", "lemma": "early", "forms": ["early"], "translations": { "es": "temprano" },
              "examples": [ { "text": "I get up early.", "translations": { "es": "Me levanto temprano." } } ] },
            { "id": "dont", "lemma": "do not", "kind": "contraction", "forms": ["don't"], "translations": { "es": "no (imperativo negativo)" } }
          ]
        }
    """.trimIndent()

    val frenchJson = """
        {
          "formatVersion": 1, "id": "fr-test", "title": "Test", "learningLanguage": "fr", "cefrLevel": "A2",
          "license": { "id": "LicenseRef-Test" }, "sourceAttribution": "Test",
          "chapters": [ { "id": "ch1", "title": "Un", "passages": [
            { "id": "p1", "sentences": [
              { "id": "s1", "text": "Qu'est-ce que je vous sers ?", "translations": { "es": "¿Qué le pongo?" } },
              { "id": "s2", "text": "C'est à toi ?" }
            ] } ] } ],
          "glossary": [
            { "id": "quest-ce-que", "lemma": "qu'est-ce que", "kind": "expression", "forms": ["qu'est-ce que"], "translations": { "es": "qué" } },
            { "id": "cest", "lemma": "c'est", "kind": "expression", "forms": ["c’est"], "translations": { "es": "es" } },
            { "id": "toi", "lemma": "toi", "forms": ["toi"], "translations": { "es": "tú / ti" } }
          ]
        }
    """.trimIndent()

    val english: Book by lazy { BookManifestParser.parse(englishJson) }
    val french: Book by lazy { BookManifestParser.parse(frenchJson) }
}
