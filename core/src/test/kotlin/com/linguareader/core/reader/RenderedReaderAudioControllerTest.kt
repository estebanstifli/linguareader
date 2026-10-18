package com.linguareader.core.reader

import com.linguareader.core.engine.AudioAsset
import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechRequest
import com.linguareader.core.engine.SpeechResult
import com.linguareader.core.engine.SpeechSynthesizer
import com.linguareader.core.engine.TtsEngineInfo
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.testing.FakeStreamingEngine
import com.linguareader.core.testing.SampleBooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeClipPlayer : ClipPlayer {
    override val events = MutableSharedFlow<ClipEvent>(extraBufferCapacity = 64)
    val queue = mutableListOf<Pair<Int, String>>()
    /** Cola completa incluyendo silencios: "clip:n" o "silence:ms". */
    val timeline = mutableListOf<String>()
    var playing = false
    var seeks = mutableListOf<Int>()
    override fun enqueue(tag: Int, path: String) { queue += tag to path; timeline += "clip:$tag" }
    override fun enqueueSilence(durationMs: Long) { timeline += "silence:$durationMs" }
    override fun clear() { queue.clear(); timeline.clear() }
    override fun play() { playing = true }
    override fun pause() { playing = false }
    override fun seekToTag(tag: Int): Boolean = queue.any { it.first == tag }.also { if (it) seeks += tag }
    var position: ClipPosition? = null
    override fun position() = position
    var appliedSpeed = 1f
    override fun setPlaybackSpeed(speed: Float) { appliedSpeed = speed }
    override fun release() {}
}

class FakeSynthesizer(
    var readiness: EngineReadiness = EngineReadiness.Ready,
    override val id: String = "fake-rendered",
) : SpeechSynthesizer {
    override val info = TtsEngineInfo(id, "Fake", streaming = false, providesWordTimings = false, requiresModelDownload = true, offline = true)
    val requests = mutableListOf<SpeechRequest>()
    var gate: CompletableDeferred<Unit>? = null
    var failWith: EngineUnavailableReason? = null
    /** Peticiones empezadas (incluye las que siguen bloqueadas en [gate] o se cancelan antes de terminar). */
    var started = 0
    /** El [gate] solo bloquea a partir de la petición número [gateAfter] (0 = desde la primera). */
    var gateAfter = 0
    /** Duración que declara cada audio generado; null = el motor no la informa. */
    var durationMs: Long? = 1000
    var released = 0
    override suspend fun synthesize(request: SpeechRequest): SpeechResult {
        val order = started++
        if (order >= gateAfter) gate?.await()
        requests += request
        failWith?.let { return SpeechResult.Failure(it, "fallo") }
        return SpeechResult.Success(AudioAsset("/clips/${request.text.hashCode()}-${request.speed}.wav", "audio/wav", durationMs, null), "v1")
    }
    override suspend fun availability(languageTag: String, voiceId: String?) = readiness
    override suspend fun listVoices(languageTag: String) = emptyList<VoiceInfo>()
    override fun release() { released++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RenderedReaderAudioControllerTest {

    private val passage = SampleBooks.english.passages.first() // 2 frases
    private val synth = FakeSynthesizer()
    private val player = FakeClipPlayer()
    private val snippets = FakeClipPlayer()

    private fun TestScope.controller() =
        RenderedReaderAudioController(synth, player, snippets, StandardTestDispatcher(testScheduler), { mapOf("steps" to "5") })
            .also { advanceUntilIdle() }

    private val RenderedReaderAudioController.state get() = playbackState.value

    @Test
    fun `play synthesizes one clip per sentence and enqueues them in order`() = runTest {
        val c = controller()
        c.load(passage, "en", "F1")
        c.play()
        advanceUntilIdle()
        assertEquals(listOf(0, 1), player.queue.map { it.first })
        assertEquals(passage.sentences.map { it.text }, synth.requests.map { it.text })
        assertTrue(synth.requests.all { it.voiceId == "F1" && it.parameters["steps"] == "5" })
        assertTrue(player.playing)
        assertEquals(PlaybackStatus.PLAYING, c.state.status)
        assertEquals(HighlightGranularity.SENTENCE, c.state.granularity)
        assertTrue(c.state.engineLacksWordTimings)
    }

    @Test
    fun `pauses are inserted between sentences and at the end of the paragraph`() = runTest {
        val c = RenderedReaderAudioController(synth, player, snippets, StandardTestDispatcher(testScheduler), pauses = { SpeechPauses(550, 1000) })
        advanceUntilIdle()
        c.load(passage, "en", null)
        c.play(); advanceUntilIdle()
        assertEquals(listOf("clip:0", "silence:550", "clip:1", "silence:1000"), player.timeline)
    }

    @Test
    fun `highlight follows clip starts and never claims word level`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceUntilIdle()
        player.events.emit(ClipEvent.Started(1)); advanceUntilIdle()
        assertEquals(1, c.state.sentenceIndex)
        assertEquals(passage.sentences[1].rangeStart, c.state.resumeOffset)
        assertNull(c.state.activeTokenId)
        assertEquals(HighlightGranularity.SENTENCE, c.state.granularity)
    }

    @Test
    fun `playback starts while later sentences are still being synthesized`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        val gate = CompletableDeferred<Unit>()
        synth.gate = gate
        c.play(); advanceUntilIdle()
        assertTrue(c.state.buffering)
        assertTrue(player.queue.isEmpty())
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(c.state.buffering)
        assertEquals(2, player.queue.size)
    }

    @Test
    fun `pause and resume reuse the queued clips without resynthesis`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceUntilIdle()
        val before = synth.requests.size
        c.pause()
        assertFalse(player.playing)
        assertEquals(PlaybackStatus.PAUSED, c.state.status)
        c.play(); advanceUntilIdle()
        assertTrue(player.playing)
        assertEquals(before, synth.requests.size)
    }

    @Test
    fun `queue end after last sentence completes the passage`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceUntilIdle()
        player.events.emit(ClipEvent.QueueEnded); advanceUntilIdle()
        assertEquals(PlaybackStatus.COMPLETED, c.state.status)
    }

    @Test
    fun `queue end while synthesis lags waits instead of completing`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceUntilIdle()
        player.clear() // simula que solo estaba la primera
        val gate = CompletableDeferred<Unit>()
        synth.gate = gate
        c.seekToSentence(0); advanceUntilIdle() // regenera: queda bloqueado en la frase 0
        player.events.emit(ClipEvent.QueueEnded); advanceUntilIdle()
        assertEquals(PlaybackStatus.PLAYING, c.state.status)
        assertTrue(c.state.buffering)
        gate.complete(Unit)
    }

    @Test
    fun `seek within queued clips does not resynthesize`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceUntilIdle()
        val before = synth.requests.size
        c.seekToSentence(1); advanceUntilIdle()
        assertEquals(listOf(1), player.seeks)
        assertEquals(before, synth.requests.size)
        assertEquals(1, c.state.sentenceIndex)
    }

    @Test
    fun `read from here starts at the sentence start, not an invented word time`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        val wordInSecond = passage.tokens.first { it.sentenceId == passage.sentences[1].id && it.index > 0 && it.text == "worry" }
        c.seekToOffset(wordInSecond.rangeStart)
        assertEquals(1, c.state.sentenceIndex)
        assertEquals(passage.sentences[1].rangeStart, c.state.resumeOffset)
    }

    @Test
    fun `speed change only changes playback and never regenerates`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceUntilIdle()
        val before = synth.requests.size
        c.setSpeed(1.25f); advanceUntilIdle()
        assertEquals(before, synth.requests.size)
        assertTrue(synth.requests.all { it.speed == 1.0f })
        assertEquals(1.25f, player.appliedSpeed)
        assertEquals(1.25f, c.state.speed)
        assertEquals(2, player.queue.size)
    }

    @Test
    fun `missing model is reported as unavailable and play is a no op`() = runTest {
        synth.readiness = EngineReadiness.Unavailable(EngineUnavailableReason.MODEL_NOT_INSTALLED, "en")
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceUntilIdle()
        assertTrue(synth.requests.isEmpty())
        assertFalse(c.state.isAudioAvailable)
    }

    @Test
    fun `synthesis failure surfaces as error with reason`() = runTest {
        synth.failWith = EngineUnavailableReason.OUT_OF_MEMORY
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceUntilIdle()
        assertEquals(PlaybackStatus.ERROR, c.state.status)
        assertEquals(EngineUnavailableReason.OUT_OF_MEMORY, (c.state.readiness as EngineReadiness.Unavailable).reason)
    }

    @Test
    fun `prefetch synthesizes the next passage after the current one`() = runTest {
        val next = SampleBooks.french.passages.first()
        val c = controller()
        c.load(passage, "en", null)
        c.play()
        c.prefetch(next, "fr", null); advanceUntilIdle()
        val texts = synth.requests.map { it.text }
        assertEquals(passage.sentences.map { it.text } + next.sentences.map { it.text }, texts)
        assertEquals(2, player.queue.size) // lo precargado no se encola
    }

    @Test
    fun `switching engine stops the previous one and releases it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val streaming = FakeStreamingEngine()
        val streamingController = StreamingReaderAudioController(streaming, AudioFocusGate.AlwaysGranted, dispatcher)
        val rendered = controller()
        val switching = SwitchingReaderAudioController(
            controllers = mapOf("fake" to streamingController, synth.id to rendered),
            engines = TtsEngineRegistry(listOf(streaming, synth)),
            defaultEngineId = synth.id,
            dispatcher = dispatcher,
        )
        advanceUntilIdle()
        switching.load(passage, "en", null)
        switching.play(); advanceUntilIdle()
        assertEquals(synth.id, switching.playbackState.value.engineId)
        switching.selectEngine("fake"); advanceUntilIdle()
        assertEquals(1, synth.released)
        assertEquals(PlaybackStatus.PAUSED, rendered.state.status)
        assertEquals("fake", switching.playbackState.value.engineId)
        assertEquals("fake", switching.selectedEngineId.value)
    }
}
