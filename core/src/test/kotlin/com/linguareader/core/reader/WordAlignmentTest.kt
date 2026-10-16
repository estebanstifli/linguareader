package com.linguareader.core.reader

import com.linguareader.core.alignment.ClipAligner
import com.linguareader.core.alignment.WordSpanMs
import com.linguareader.core.testing.SampleBooks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aligner de prueba: cada palabra dura 300 ms, empezando en 100 ms. */
private class FakeAligner(var available: Boolean = true, var spans: ((List<String>) -> List<WordSpanMs>?)? = null) : ClipAligner {
    override val id = "fake"
    var calls = 0
    var released = 0
    override fun isAvailable(languageTag: String) = available
    override suspend fun align(clipPath: String, words: List<String>, languageTag: String): List<WordSpanMs>? {
        calls++
        return spans?.invoke(words) ?: words.mapIndexed { i, _ -> WordSpanMs(i, 100L + 300 * i, 100L + 300 * i + 250, 0.9f) }
    }
    override fun release() { released++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WordAlignmentTest {

    private val passage = SampleBooks.english.passages.first()
    private val synth = FakeSynthesizer()
    private val player = FakeClipPlayer()
    private val aligner = FakeAligner()

    private fun TestScope.controller(al: ClipAligner? = aligner) =
        RenderedReaderAudioController(
            synth, player, FakeClipPlayer(), StandardTestDispatcher(testScheduler),
            aligner = al, alignDispatcher = StandardTestDispatcher(testScheduler), tickMs = 10,
        ).also { advanceUntilIdle() }

    @Test
    fun `aligned clip highlights the word under the playhead`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceTimeBy(50)
        val tokens = passage.tokensOf(passage.sentences[0])
        player.position = ClipPosition(0, 50) // antes de la primera palabra
        advanceTimeBy(30)
        assertNull(c.playbackState.value.activeTokenId)
        assertEquals(HighlightGranularity.SENTENCE, c.playbackState.value.granularity)
        player.position = ClipPosition(0, 100L + 300 * 2 + 10)
        advanceTimeBy(30)
        assertEquals(tokens[2].id, c.playbackState.value.activeTokenId)
        assertEquals(HighlightGranularity.WORD, c.playbackState.value.granularity)
        assertEquals(tokens[2].rangeStart, c.playbackState.value.resumeOffset)
        c.pause(); advanceUntilIdle()
    }

    @Test
    fun `alignment never delays enqueueing the clips`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceTimeBy(1)
        assertTrue(player.queue.isNotEmpty())
        c.pause(); advanceUntilIdle()
    }

    @Test
    fun `without aligner or model the highlight stays at sentence level`() = runTest {
        for (al in listOf(null, FakeAligner(available = false))) {
            val c = controller(al)
            c.load(passage, "en", null)
            c.play(); advanceTimeBy(50)
            player.position = ClipPosition(0, 400)
            advanceTimeBy(50)
            assertNull(c.playbackState.value.activeTokenId)
            assertTrue(c.playbackState.value.granularity != HighlightGranularity.WORD)
            c.pause(); advanceUntilIdle()
        }
    }

    @Test
    fun `invalid alignment is discarded and falls back to sentence`() = runTest {
        aligner.spans = { words -> words.mapIndexed { i, _ -> WordSpanMs(i, 1000L - 100 * i, 1100L - 100 * i, 0.9f) } } // no monótona
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceTimeBy(50)
        player.position = ClipPosition(0, 500)
        advanceTimeBy(50)
        assertNull(c.playbackState.value.activeTokenId)
        c.pause(); advanceUntilIdle()
    }

    @Test
    fun `failing aligner does not break playback`() = runTest {
        aligner.spans = { error("boom") }
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceTimeBy(50)
        assertEquals(PlaybackStatus.PLAYING, c.playbackState.value.status)
        assertEquals(listOf(0, 1), player.queue.map { it.first })
        c.pause(); advanceUntilIdle()
    }

    @Test
    fun `stop releases the aligner model`() = runTest {
        val c = controller()
        c.load(passage, "en", null)
        c.play(); advanceTimeBy(50)
        c.stop(); advanceUntilIdle()
        assertEquals(1, aligner.released)
    }
}
