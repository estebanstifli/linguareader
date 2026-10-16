package com.linguareader.core.reader

import com.linguareader.core.engine.EngineReadiness
import com.linguareader.core.engine.EngineUnavailableReason
import com.linguareader.core.engine.SpeechEvent
import com.linguareader.core.testing.FakeStreamingEngine
import com.linguareader.core.testing.SampleBooks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingReaderAudioControllerTest {

    private val passage = SampleBooks.english.passages.first()
    // "Tom wakes up early. Don't worry, Anna."
    private val engine = FakeStreamingEngine()

    private fun TestScope.controller(): StreamingReaderAudioController =
        StreamingReaderAudioController(engine, AudioFocusGate.AlwaysGranted, StandardTestDispatcher(testScheduler))
            .also { advanceUntilIdle() }

    private val StreamingReaderAudioController.state get() = playbackState.value

    @Test
    fun `play enqueues one utterance per sentence`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play()
        assertEquals(listOf("Tom wakes up early.", "Don't worry, Anna."), engine.enqueued.map { it.text })
        assertTrue(engine.enqueued.first().flush)
        assertFalse(engine.enqueued[1].flush)
        assertEquals(PlaybackStatus.PLAYING, c.state.status)
        assertEquals(HighlightGranularity.SENTENCE, c.state.granularity)
    }

    @Test
    fun `word events produce word highlight, otherwise sentence highlight`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play()
        val first = engine.enqueued[0].utteranceId
        engine.emit(SpeechEvent.Started(first)); advanceUntilIdle()
        assertEquals(HighlightGranularity.SENTENCE, c.state.granularity)
        assertNull(c.state.activeTokenId)

        engine.emit(SpeechEvent.WordRange(first, 4, 9)); advanceUntilIdle() // "wakes"
        assertEquals(HighlightGranularity.WORD, c.state.granularity)
        assertEquals("wakes", passage.tokenById(c.state.activeTokenId!!)?.text)
        assertTrue(c.state.wordEventsObserved)
    }

    @Test
    fun `incoherent word events fall back to sentence highlight for that sentence`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play()
        val id = engine.enqueued[0].utteranceId
        engine.emit(SpeechEvent.Started(id))
        engine.emit(SpeechEvent.WordRange(id, 10, 12)) // "up"
        engine.emit(SpeechEvent.WordRange(id, 4, 9)) // retrocede: no monótono
        advanceUntilIdle()
        assertEquals(HighlightGranularity.SENTENCE, c.state.granularity)
        assertNull(c.state.activeTokenId)
        engine.emit(SpeechEvent.WordRange(id, 13, 18)); advanceUntilIdle() // ignorado en esta frase
        assertNull(c.state.activeTokenId)

        val out = SpeechEvent.WordRange(engine.enqueued[1].utteranceId, 0, 999)
        engine.emit(SpeechEvent.Started(out.utteranceId)); engine.emit(out); advanceUntilIdle()
        assertEquals(HighlightGranularity.SENTENCE, c.state.granularity)
    }

    @Test
    fun `pause and resume restart from the last heard word`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play()
        val id = engine.enqueued[0].utteranceId
        engine.emit(SpeechEvent.Started(id))
        engine.emit(SpeechEvent.WordRange(id, 10, 12)) // "up"
        advanceUntilIdle()
        c.pause()
        assertEquals(PlaybackStatus.PAUSED, c.state.status)
        assertEquals(passage.text.indexOf("up early"), c.state.resumeOffset)

        // eventos tardíos de la generación anterior se ignoran
        engine.emit(SpeechEvent.WordRange(id, 13, 18)); advanceUntilIdle()
        assertEquals("up", passage.tokenById(c.state.activeTokenId!!)?.text)

        c.play()
        assertEquals("up early.", engine.enqueued[0].text)
        val resumed = engine.enqueued[0].utteranceId
        engine.emit(SpeechEvent.Started(resumed))
        engine.emit(SpeechEvent.WordRange(resumed, 3, 8)); advanceUntilIdle() // "early" relativo a "up early."
        assertEquals("early", passage.tokenById(c.state.activeTokenId!!)?.text)
    }

    @Test
    fun `completion of last sentence completes passage`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play()
        val (a, b) = engine.enqueued.map { it.utteranceId }
        engine.emit(SpeechEvent.Started(a)); engine.emit(SpeechEvent.Done(a)); advanceUntilIdle()
        assertEquals(PlaybackStatus.PLAYING, c.state.status)
        assertEquals(passage.sentences[1].rangeStart, c.state.resumeOffset)
        engine.emit(SpeechEvent.Started(b)); engine.emit(SpeechEvent.Done(b)); advanceUntilIdle()
        assertEquals(PlaybackStatus.COMPLETED, c.state.status)
        assertEquals(HighlightGranularity.NONE, c.state.granularity)
    }

    @Test
    fun `seek to sentence while playing restarts from that sentence`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play()
        c.seekToSentence(1)
        assertEquals(PlaybackStatus.PLAYING, c.state.status)
        assertEquals(listOf("Don't worry, Anna."), engine.enqueued.map { it.text })
        assertEquals(1, c.state.sentenceIndex)
    }

    @Test
    fun `load restores the saved offset`() = runTest {
        val c = controller()
        c.load(passage, "en", null, startOffset = passage.sentences[1].rangeStart)
        assertEquals(1, c.state.sentenceIndex)
        c.play()
        assertEquals(listOf("Don't worry, Anna."), engine.enqueued.map { it.text })
    }

    @Test
    fun `speed change while playing re-enqueues with new speed`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play()
        c.setSpeed(1.25f)
        assertEquals(1.25f, engine.enqueued.first().speed)
        c.setSpeed(9f)
        assertEquals(1.5f, c.state.speed)
    }

    @Test
    fun `unavailable voice keeps reader usable without audio`() = runTest {
        engine.readiness = EngineReadiness.Unavailable(EngineUnavailableReason.LANGUAGE_DATA_MISSING, "fr-FR")
        val c = controller()
        c.load(passage, "fr-FR", null)
        c.play()
        assertTrue(engine.enqueued.isEmpty())
        assertEquals(PlaybackStatus.READY, c.state.status)
        assertFalse(c.state.isAudioAvailable)
    }

    @Test
    fun `engine error stops playback`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play()
        engine.emit(SpeechEvent.Error(engine.enqueued[0].utteranceId, -3)); advanceUntilIdle()
        assertEquals(PlaybackStatus.ERROR, c.state.status)
        assertEquals(-3, c.state.errorCode)
    }

    @Test
    fun `time based seek is reported as unsupported`() = runTest {
        val c = controller()
        assertFalse(c.seekTo(1000))
        assertFalse(c.state.canSeekByTime)
    }
}
